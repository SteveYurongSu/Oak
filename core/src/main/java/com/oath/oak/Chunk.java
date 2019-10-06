/*
 * Copyright 2018 Oath Inc.
 * Licensed under the terms of the Apache 2.0 license.
 * Please see LICENSE file in the project root for terms.
 */

package com.oath.oak;

import sun.misc.Unsafe;

import java.nio.ByteBuffer;
import java.util.EmptyStackException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.oath.oak.NativeAllocator.OakNativeMemoryAllocator.INVALID_BLOCK_ID;
import static com.oath.oak.UnsafeUtils.intsToLong;
import static com.oath.oak.UnsafeUtils.longToInts;

public class Chunk<K, V> {

    /*-------------- Constants --------------*/

    /***
     * This enum is used to access the different fields in each entry.
     * The value associated with each entry signifies the offset of the field relative to the entry's beginning.
     */
    enum OFFSET {
        /***
         * NEXT - the next index of this entry (one integer)
         *
         * VALUE_REFERENCE - the blockID, length and position of the value pointed from this entry (size of two
         * integers, one long). Equals to DELETED_VALUE if no value is point.
         *
         * VALUE_POSITION
         *
         * VALUE_BLOCK_AND_LENGTH - this value holds both the blockID and the length of the value pointed by the entry.
         * Using VALUE_LENGTH_MASK and VALUE_BLOCK_SHIFT the blockID and length can be extracted.
         * Currently, the length of a value is limited to 8MB, and blockID is limited to 512 blocks
         * (with the current block size of 256MB, the total memory is up to 128GB).
         *
         * VALUE_BLOCK
         *
         * VALUE_LENGTH
         *
         * KEY_POSITION
         *
         * KEY_BLOCK_AND_LENGTH - similar to VALUE_BLOCK_AND_LENGTH, but using KEY_LENGTH_MASK and KEY_BLOCK_SHIFT.
         * The length of a key is limited to 32KB.
         *
         * KEY_BLOCK
         *
         * KEY_LENGTH
         */
        NEXT(0), VALUE_REFERENCE(1), VALUE_POSITION(1), VALUE_BLOCK_AND_LENGTH(2), VALUE_BLOCK(2),
        VALUE_LENGTH(2), KEY_REFERENCE(3), KEY_POSITION(3), KEY_BLOCK_AND_LENGTH(4), KEY_BLOCK(4),
        KEY_LENGTH(4);

        public final int value;

        OFFSET(int value) {
            this.value = value;
        }
    }

    enum State {
        INFANT,
        NORMAL,
        FROZEN,
        RELEASED
    }

    static final int NONE = 0;    // an entry with NONE as its next pointer, points to a null entry
    static final long DELETED_VALUE = 0;
    static final int BLOCK_ID_LENGTH_ARRAY_INDEX = 0;
    static final int POSITION_ARRAY_INDEX = 1;
    // location of the first (head) node - just a next pointer
    private static final int HEAD_NODE = 0;
    // index of first item in array, after head (not necessarily first in list!)
    private static final int FIRST_ITEM = 1;

    private static final int FIELDS = 6;  // # of fields in each item of key array
    private static final int KEY_LENGTH_MASK = 0xffff; // 16 lower bits
    private static final int KEY_BLOCK_SHIFT = 16;
    // Assume the length of a value is up to 8MB because there can be up to 512 blocks
    static final int VALUE_LENGTH_MASK = 0x7fffff;
    static final int VALUE_BLOCK_SHIFT = 23;

    // used for checking if rebalance is needed
    private static final double REBALANCE_PROB_PERC = 30;
    private static final double SORTED_REBALANCE_RATIO = 2;
    private static final double MAX_ENTRIES_FACTOR = 2;
    private static final double MAX_IDLE_ENTRIES_FACTOR = 5;

    // defaults
    public static final int MAX_ITEMS_DEFAULT = 4096;

    private static final Unsafe unsafe = UnsafeUtils.unsafe;
    private final MemoryManager memoryManager;
    ByteBuffer minKey;       // minimal key that can be put in this chunk
    AtomicMarkableReference<Chunk<K, V>> next;
    OakComparator<K> comparator;

    // in split/compact process, represents parent of split (can be null!)
    private AtomicReference<Chunk<K, V>> creator;
    // chunk can be in the following states: normal, frozen or infant(has a creator)
    private final AtomicReference<State> state;
    private AtomicReference<Rebalancer<K, V>> rebalancer;
    private final int[] entries;    // array is initialized to 0, i.e., NONE - this is important!

    private AtomicInteger pendingOps;
    private final AtomicInteger entryIndex;    // points to next free index of entry array
    private final Statistics statistics;
    // # of sorted items at entry-array's beginning (resulting from split)
    private AtomicInteger sortedCount;
    private final int maxItems;
    AtomicInteger externalSize; // for updating oak's size
    // for writing the keys into the bytebuffers
    private final OakSerializer<K> keySerializer;
    private final OakSerializer<V> valueSerializer;

    /*-------------- Constructors --------------*/

    /**
     * Create a new chunk
     *
     * @param minKey  minimal key to be placed in chunk
     * @param creator the chunk that is responsible for this chunk creation
     */
    Chunk(ByteBuffer minKey, Chunk<K, V> creator, OakComparator<K> comparator, MemoryManager memoryManager,
          int maxItems, AtomicInteger externalSize,
          OakSerializer<K> keySerializer, OakSerializer<V> valueSerializer) {
        this.memoryManager = memoryManager;
        this.maxItems = maxItems;
        this.entries = new int[maxItems * FIELDS + FIRST_ITEM];
        this.entryIndex = new AtomicInteger(FIRST_ITEM);

        this.sortedCount = new AtomicInteger(0);
        this.minKey = minKey;
        this.creator = new AtomicReference<>(creator);
        if (creator == null) {
            this.state = new AtomicReference<>(State.NORMAL);
        } else {
            this.state = new AtomicReference<>(State.INFANT);
        }
        this.next = new AtomicMarkableReference<>(null, false);
        this.pendingOps = new AtomicInteger();
        this.rebalancer = new AtomicReference<>(null); // to be updated on rebalance
        this.statistics = new Statistics();
        this.comparator = comparator;
        this.externalSize = externalSize;

        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
    }

    static class OpData {
        final Operation op;
        final int entryIndex;
        final long newValueReference;
        long oldValueReference;
        final Consumer<OakWBuffer> computer;

        OpData(Operation op, int entryIndex, long newValueReference, long oldValueReference,
               Consumer<OakWBuffer> computer) {
            this.op = op;
            this.entryIndex = entryIndex;
            this.newValueReference = newValueReference;
            this.oldValueReference = oldValueReference;
            this.computer = computer;
        }
    }

    /*-------------- Methods --------------*/

    void release() {
        // try to change the state
        state.compareAndSet(State.FROZEN, State.RELEASED);
    }

    /**
     * performs CAS from 'expected' to 'value' for field at specified offset of given item in key array
     */
    private boolean casEntriesArrayInt(int item, OFFSET offset, int expected, int value) {
        return unsafe.compareAndSwapInt(entries,
                Unsafe.ARRAY_INT_BASE_OFFSET + (item + offset.value) * Unsafe.ARRAY_INT_INDEX_SCALE,
                expected, value);
    }

    boolean casEntriesArrayLong(int item, OFFSET offset, long expected, long value) {
        return unsafe.compareAndSwapLong(entries,
                Unsafe.ARRAY_INT_BASE_OFFSET + (item + offset.value) * Unsafe.ARRAY_INT_INDEX_SCALE,
                expected, value);
    }


    /**
     * write key in slice
     **/
    private void writeKey(K key, int ei) {
        int keySize = keySerializer.calculateSize(key);
        Slice s = memoryManager.allocateSlice(keySize);
        // byteBuffer.slice() is set so it protects us from the overwrites of the serializer
        keySerializer.serialize(key, s.getByteBuffer().slice());

        setEntryFieldInt(ei, OFFSET.KEY_BLOCK, s.getBlockID());
        setEntryFieldInt(ei, OFFSET.KEY_POSITION, s.getByteBuffer().position());
        setEntryFieldInt(ei, OFFSET.KEY_LENGTH, keySize);
    }

    /**
     * Reads a key given the entry index. Key is returned via reusable thread-local ByteBuffer.
     * There is no copy just a special ByteBuffer for a single key.
     * The thread-local ByteBuffer can be reused by different threads, however as long as
     * a thread is invoked the ByteBuffer is related solely to this thread.
     */
    ByteBuffer readKey(int entryIndex) {
        if (entryIndex == Chunk.NONE) {
            return null;
        }

        long keyReference = getKeyReference(entryIndex);
        int[] keyArray = longToInts(keyReference);
        int blockID = keyArray[0] >> KEY_BLOCK_SHIFT;
        int keyPosition = keyArray[1];
        int length = keyArray[0] & KEY_LENGTH_MASK;

        return memoryManager.getByteBufferFromBlockID(blockID, keyPosition, length);
    }

    /**
     * Sets the given key reference (OakRKeyReferBufferImpl) given the entry index.
     * There is no copy just a special ByteBuffer for a single key.
     * The thread-local ByteBuffer can be reused by different threads, however as long as
     * a thread is invoked the ByteBuffer is related solely to this thread.
     */
    void setKeyRefer(int entryIndex, OakRReference keyReferBuffer) {
        if (entryIndex == Chunk.NONE) {
            return;
        }
        long keyReference = getKeyReference(entryIndex);
        int[] keyArray = longToInts(keyReference);
        int blockID = keyArray[BLOCK_ID_LENGTH_ARRAY_INDEX] >> KEY_BLOCK_SHIFT;
        int keyPosition = keyArray[POSITION_ARRAY_INDEX];
        int length = keyArray[BLOCK_ID_LENGTH_ARRAY_INDEX] & KEY_LENGTH_MASK;
        keyReferBuffer.setReference(blockID, keyPosition, length);
    }

    boolean setValueRefer(int entryIndex, OakRReference value) {
        if (entryIndex == Chunk.NONE) {
            return false;
        }
        long valueReference = getValueReference(entryIndex);
        int[] valueArray = longToInts(valueReference);
        int blockID = valueArray[BLOCK_ID_LENGTH_ARRAY_INDEX] >> VALUE_BLOCK_SHIFT;
        if (blockID == INVALID_BLOCK_ID) {
            return false;
        }
        int keyPosition = valueArray[POSITION_ARRAY_INDEX];
        int length = valueArray[BLOCK_ID_LENGTH_ARRAY_INDEX] & VALUE_LENGTH_MASK;
        value.setReference(blockID, keyPosition, length);
        return true;
    }

    /**
     * release key in slice, currently not in use, waiting for GC to be arranged
     **/
    void releaseKey(int entryIndex) {
        long keyReference = getKeyReference(entryIndex);
        int[] keyArray = longToInts(keyReference);
        int blockID = keyArray[0] >> KEY_BLOCK_SHIFT;
        int keyPosition = keyArray[1];
        int length = keyArray[0] & KEY_LENGTH_MASK;
        Slice s = new Slice(blockID, keyPosition, length, memoryManager);

        memoryManager.releaseSlice(s);
    }

    ByteBuffer readMinKey() {
        int minEntry = getFirstItemEntryIndex();
        return readKey(minEntry);
    }

    ByteBuffer readMaxKey() {
        int maxEntry = getLastItemEntryIndex();
        return readKey(maxEntry);
    }

    /**
     * gets the field of specified offset for given item in entry array
     */
    private int getEntryFieldInt(int item, OFFSET offset) {
        switch (offset) {
            case KEY_LENGTH:
                // return two low bytes of the key length index int
                return (entries[item + offset.value] & KEY_LENGTH_MASK);
            case KEY_BLOCK:
                // offset must be OFFSET_KEY_BLOCK, return 2 high bytes of the int inside key length
                // right-shift force, fill empty with zeroes
                return (entries[item + offset.value] >>> KEY_BLOCK_SHIFT);
            case VALUE_LENGTH:
                return (entries[item + offset.value] & VALUE_LENGTH_MASK);
            case VALUE_BLOCK:
                return (entries[item + offset.value] >>> VALUE_BLOCK_SHIFT);
            default:
                return entries[item + offset.value];
        }
    }

    // Atomically reads two integers of the entries array.
    // Should be used with OFFSET.VALUE_REFERENCE and OFFSET.KEY_REFERENCE
    private long getEntryFieldLong(int item, OFFSET offset) {
        long arrayOffset = Unsafe.ARRAY_INT_BASE_OFFSET + (item + offset.value) * Unsafe.ARRAY_INT_INDEX_SCALE;
        assert arrayOffset % 8 == 0;
        return unsafe.getLongVolatile(entries, arrayOffset);
    }

    /**
     * * sets the field of specified offset to 'value' for given item in entry array
     * NOT ATOMIC!
     * It can be used only on fields which do not change through out the entry's life, i.e. key reference, or it can
     * be used when the entry is not yet linked.
     */
    private void setEntryFieldInt(int item, OFFSET offset, int value) {
        assert item + offset.value >= 0;
        switch (offset) {
            case KEY_LENGTH:
                // OFFSET_KEY_LENGTH and OFFSET_KEY_BLOCK should be less then 16 bits long
                // *2 in order to get read of the signed vs unsigned limits
                assert value < Short.MAX_VALUE * 2;
                // set two low bytes of the key block id and length index
                entries[item + offset.value] =
                        (entries[item + offset.value]) | (value & KEY_LENGTH_MASK);
                return;
            case KEY_BLOCK:
                // OFFSET_KEY_LENGTH and OFFSET_KEY_BLOCK should be less then 16 bits long
                // *2 in order to get read of the signed vs unsigned limits
                assert value < Short.MAX_VALUE * 2;
                // offset must be OFFSET_KEY_BLOCK,
                // set 2 high bytes of the int inside OFFSET_KEY_LENGTH
                assert value > 0; // block id can never be 0
                entries[item + offset.value] =
                        (entries[item + offset.value]) | (value << KEY_BLOCK_SHIFT);
                return;
            case VALUE_LENGTH:
                // make sure the length is at most 2^23 and at least 0
                assert (value & VALUE_LENGTH_MASK) == value;
                entries[item + offset.value] =
                        (entries[item + offset.value]) | (value & VALUE_LENGTH_MASK);
                return;
            case VALUE_BLOCK:
                assert value > 0; // block id can never be 0
                assert ((value << VALUE_BLOCK_SHIFT) >>> VALUE_BLOCK_SHIFT) == value; // value is up to 2^9
                entries[item + offset.value] =
                        (entries[item + offset.value]) | (value << VALUE_BLOCK_SHIFT);
                return;
            default:
                entries[item + offset.value] = value;
        }
    }

    private void setEntryFieldLong(int item, OFFSET offset, long value) {
        long arrayOffset = Unsafe.ARRAY_INT_BASE_OFFSET + (item + offset.value) * Unsafe.ARRAY_INT_INDEX_SCALE;
        assert arrayOffset % 8 == 0;
        unsafe.putLongVolatile(entries, arrayOffset, value);
    }

    /**
     * gets the value for the given item, or 'null' if it doesn't exist
     */
    Slice getValueSlice(int entryIndex) {
        return buildValueSlice(getValueReference(entryIndex));
    }

    Slice buildValueSlice(long valueReference) {
        int[] valueArray = UnsafeUtils.longToInts(valueReference);
        if ((valueArray[BLOCK_ID_LENGTH_ARRAY_INDEX] >>> VALUE_BLOCK_SHIFT) == INVALID_BLOCK_ID) {
            return null;
        }
        return new Slice(valueArray[BLOCK_ID_LENGTH_ARRAY_INDEX] >>> VALUE_BLOCK_SHIFT,
                valueArray[POSITION_ARRAY_INDEX], valueArray[BLOCK_ID_LENGTH_ARRAY_INDEX] & VALUE_LENGTH_MASK,
                memoryManager);
    }

    // Atomically reads the value reference from the entry array
    long getValueReference(int entryIndex) {
        return getEntryFieldLong(entryIndex, OFFSET.VALUE_REFERENCE);
    }

    // Atomically reads the value reference from the entry array
    long getKeyReference(int entryIndex) {
        return getEntryFieldLong(entryIndex, OFFSET.KEY_REFERENCE);
    }

    void releaseValue(long newValueReference) {
        memoryManager.releaseSlice(buildValueSlice(newValueReference));
    }

    /**
     * look up key
     */
    LookUp lookUp(K key) {
        // binary search sorted part of key array to quickly find node to start search at
        // it finds previous-to-key so start with its next
        int curr = getEntryFieldInt(binaryFind(key), OFFSET.NEXT);
        int cmp;
        // iterate until end of list (or key is found)

        while (curr != NONE) {
            // compare current item's key to searched key
            cmp = comparator.compareKeyAndSerializedKey(key, readKey(curr));
            // if item's key is larger - we've exceeded our key
            // it's not in chunk - no need to search further
            if (cmp < 0) {
                return null;
            }
            // if keys are equal - we've found the item
            else if (cmp == 0) {
                long valueReference = getValueReference(curr);
                Slice valueSlice = buildValueSlice(valueReference);
                if (valueSlice == null) {
                    assert valueReference == 0;
                    return new LookUp(null, valueReference, curr);
                }
                if (ValueUtils.isValueDeleted(valueSlice)) {
                    return new LookUp(null, valueReference, curr);
                }
                return new LookUp(valueSlice, valueReference, curr);
            }
            // otherwise- proceed to next item
            else {
                curr = getEntryFieldInt(curr, OFFSET.NEXT);
            }
        }
        return null;
    }

    static class LookUp {

        Slice valueSlice;
        final long valueReference;
        final int entryIndex;

        LookUp(Slice valueSlice, long valueReference, int entryIndex) {
            this.valueSlice = valueSlice;
            this.valueReference = valueReference;
            this.entryIndex = entryIndex;
        }
    }


    /**
     * binary search for largest-entry smaller than 'key' in sorted part of key array.
     *
     * @return the index of the entry from which to start a linear search -
     * if key is found, its previous entry is returned!
     */
    private int binaryFind(K key) {
        int sortedCount = this.sortedCount.get();
        // if there are no sorted keys, or the first item is already larger than key -
        // return the head node for a regular linear search
        if ((sortedCount == 0) || comparator.compareKeyAndSerializedKey(key, readKey(FIRST_ITEM)) <= 0) {
            return HEAD_NODE;
        }

        // optimization: compare with last key to avoid binary search
        if (comparator.compareKeyAndSerializedKey(key, readKey((sortedCount - 1) * FIELDS + FIRST_ITEM)) > 0) {
            return (sortedCount - 1) * FIELDS + FIRST_ITEM;
        }

        int start = 0;
        int end = sortedCount;

        while (end - start > 1) {
            int curr = start + (end - start) / 2;

            if (comparator.compareKeyAndSerializedKey(key, readKey(curr * FIELDS + FIRST_ITEM)) <= 0) {
                end = curr;
            } else {
                start = curr;
            }
        }

        return start * FIELDS + FIRST_ITEM;
    }

    /**
     * publish operation into thread array
     * if CAS didn't succeed then this means that a rebalancer got here first and entry is frozen
     *
     * @return result of CAS
     **/
    boolean publish() {
        pendingOps.incrementAndGet();
        State currentState = state.get();
        if (currentState == State.FROZEN || currentState == State.RELEASED) {
            pendingOps.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * unpublish operation from thread array
     * if CAS didn't succeed then this means that a rebalancer did this already
     **/
    void unpublish() {
        pendingOps.decrementAndGet();
    }

    int allocateEntryAndKey(K key) {
        int ei = entryIndex.getAndAdd(FIELDS);
        if (ei + FIELDS > entries.length) {
            return -1;
        }

        // key and value must be set before linking to the list so it will make sense when reached before put is done
        setEntryFieldLong(ei, OFFSET.VALUE_REFERENCE, DELETED_VALUE); // setting the value reference to DELETED_VALUE
        // atomically
        writeKey(key, ei);
        return ei;
    }

    int linkEntry(int ei, K key) {
        int prev, curr, cmp;
        int anchor = -1;

        while (true) {
            // start iterating from quickly-found node (by binary search) in sorted part of order-array
            if (anchor == -1) {
                anchor = binaryFind(key);
            }
            curr = anchor;

            // iterate items until key's position is found
            while (true) {
                prev = curr;
                curr = getEntryFieldInt(prev, OFFSET.NEXT);    // index of next item in list

                // if no item, done searching - add to end of list
                if (curr == NONE) {
                    break;
                }
                // compare current item's key to ours
                cmp = comparator.compareKeyAndSerializedKey(key, readKey(curr));

                // if current item's key is larger, done searching - add between prev and curr
                if (cmp < 0) {
                    break;
                }

                // if same key, someone else managed to add the key to the linked list
                if (cmp == 0) {
                    return curr;
                }
            }

            // link to list between next and previous
            // first change this key's next to point to curr
            setEntryFieldInt(ei, OFFSET.NEXT, curr); // no need for CAS since put is not even published yet
                if (casEntriesArrayInt(prev, OFFSET.NEXT, curr, ei)) {
                    // Here is the single place where we do enter a new entry to the chunk, meaning
                    // there is none else simultaneously inserting the same key
                    // (we were the first to insert this key).
                    // If the new entry's index is exactly after the sorted count and
                    // the entry's key is greater or equal then to the previous (sorted count)
                    // index key. Then increase the sorted count.
                    int sortedCount = this.sortedCount.get();
                    if (sortedCount > 0) {
                        if (ei == (sortedCount * FIELDS + 1)) {
                            // the new entry's index is exactly after the sorted count
                            if (comparator.compareKeyAndSerializedKey(
                                    key, readKey((sortedCount - 1) * FIELDS + FIRST_ITEM))  >= 0) {
                                // compare with sorted count key, if inserting the "if-statement",
                                // the sorted count key is less or equal to the key just inserted
                                this.sortedCount.compareAndSet(sortedCount, (sortedCount + 1));
                            }
                        }
                    }
                    return ei;
                }
                // CAS didn't succeed, try again
        }
    }

    /**
     * write value in place
     **/
    long writeValueOffHeap(V value) {
        // the length of the given value plus its header
        int valueLength = valueSerializer.calculateSize(value) + ValueUtils.VALUE_HEADER_SIZE;
        Slice slice = memoryManager.allocateSlice(valueLength);
        // initializing the header lock to be free
        slice.initHeader();
        // since this is a private environment we can only use slice, instead of duplicate and then slice
        valueSerializer.serialize(value, ValueUtils.getActualValueBufferPrivate(slice.getByteBuffer()));
        // combines the blockID with the value's length (including the header)
        int valueBlockAndLength = (slice.getBlockID() << VALUE_BLOCK_SHIFT) | (valueLength & VALUE_LENGTH_MASK);
        return intsToLong(valueBlockAndLength, slice.getByteBuffer().position());
    }

    int getMaxItems() {
        return maxItems;
    }

    /**
     * Updates a linked entry to point to a new value reference or otherwise removes such a link. For linkage this is
     * an insert linearization point.
     * All the relevant data can be found inside opData.
     * <p>
     * return true if operation was successful, false to indicate restart required
     */
    boolean pointToValue(OpData opData) {

        // try to perform the CAS according to operation data (opData)
        if (pointToValueCAS(opData)) {
            return true;
        }

        // the straight forward helping didn't work, check why
        Operation operation = opData.op;

        // the operation is remove, means we tried to change the value reference we knew about to DELETED_VALUE
        // the old value reference is no longer there so we have nothing to do. Note that in case of non-ZC removal the
        // loop in remove() will lookup the key again so we will still return the previous value
        if (operation == Operation.REMOVE) {
            return true; // this is a remove, no need to try again and return doesn't matter
        }

        // the operation is either NO_OP, PUT, PUT_IF_ABSENT, COMPUTE
        long expectedValueReference = opData.newValueReference;
        long foundValueReference = getValueReference(opData.entryIndex);
        int foundValueBlockAndLength = UnsafeUtils.longToInts(foundValueReference)[BLOCK_ID_LENGTH_ARRAY_INDEX];

        if (expectedValueReference == foundValueReference) {
            return true; // someone helped
        } else if (foundValueBlockAndLength == INVALID_BLOCK_ID) {
            // the value reference was deleted, retry the attach
            opData.oldValueReference = DELETED_VALUE;
            return pointToValue(opData); // remove completed, try again
        } else if (operation == Operation.PUT_IF_ABSENT) {
            return false; // too late
        } else if (operation == Operation.COMPUTE) {
            Slice valueSlice = buildValueSlice(foundValueReference);
            ValueUtils.ValueResult succ = ValueUtils.compute(valueSlice, opData.computer);

            if (succ != ValueUtils.ValueResult.SUCCESS) {
                // we tried to perform the compute but the handle was deleted,
                // we can get to pointToValue with Operation.COMPUTE only from PIACIP
                // retry to make a put and to attach the new handle
                opData.oldValueReference = foundValueReference;
                return pointToValue(opData);
            }
        }
        // This is a put, in which case operation should restart,
        // or PIACIP compute happened, which should return false
        return false;
    }

    /**
     * used by put/putIfAbsent/remove and rebalancer
     */
    private boolean pointToValueCAS(OpData opData) {
        if (casEntriesArrayLong(opData.entryIndex, OFFSET.VALUE_REFERENCE, opData.oldValueReference,
                opData.newValueReference)) {
            // update statistics only by thread that CASed
            int oldValueBlock = UnsafeUtils.longToInts(opData.oldValueReference)[BLOCK_ID_LENGTH_ARRAY_INDEX];
            int newValueBlock = UnsafeUtils.longToInts(opData.newValueReference)[BLOCK_ID_LENGTH_ARRAY_INDEX];
            if (oldValueBlock == INVALID_BLOCK_ID && newValueBlock != INVALID_BLOCK_ID) { // previously a remove
                statistics.incrementAddedCount();
                externalSize.incrementAndGet();
            } else if (oldValueBlock != INVALID_BLOCK_ID && newValueBlock == INVALID_BLOCK_ID) { // removing
                statistics.decrementAddedCount();
                externalSize.decrementAndGet();
            }
            return true;
        }
        return false;
    }

    /**
     * Engage the chunk to a rebalancer r.
     *
     * @param r -- a rebalancer to engage with
     */
    void engage(Rebalancer<K, V> r) {
        rebalancer.compareAndSet(null, r);
    }

    /**
     * Checks whether the chunk is engaged with a given rebalancer.
     *
     * @param r -- a rebalancer object. If r is null, verifies that the chunk is not engaged to any rebalancer
     * @return true if the chunk is engaged with r, false otherwise
     */
    boolean isEngaged(Rebalancer<K, V> r) {
        return rebalancer.get() == r;
    }

    /**
     * Fetch a rebalancer engaged with the chunk.
     *
     * @return rebalancer object or null if not engaged.
     */
    Rebalancer<K, V> getRebalancer() {
        return rebalancer.get();
    }

    Chunk<K, V> creator() {
        return creator.get();
    }

    State state() {
        return state.get();
    }

    private void setState(State state) {
        this.state.set(state);
    }

    void normalize() {
        state.compareAndSet(State.INFANT, State.NORMAL);
        creator.set(null);
        // using fence so other puts can continue working immediately on this chunk
        Chunk.unsafe.storeFence();
    }

    final int getFirstItemEntryIndex() {
        return getEntryFieldInt(HEAD_NODE, OFFSET.NEXT);
    }

    private int getLastItemEntryIndex() {
        // find the last sorted entry
        int sortedCount = this.sortedCount.get();
        int entryIndex = sortedCount == 0 ? HEAD_NODE : (sortedCount - 1) * (FIELDS) + 1;
        int nextEntryIndex = getEntryFieldInt(entryIndex, OFFSET.NEXT);
        while (nextEntryIndex != Chunk.NONE) {
            entryIndex = nextEntryIndex;
            nextEntryIndex = getEntryFieldInt(entryIndex, OFFSET.NEXT);
        }
        return entryIndex;
    }

    /**
     * freezes chunk so no more changes can be done to it (marks pending items as frozen)
     */
    void freeze() {
        setState(State.FROZEN); // prevent new puts to this chunk
        while (pendingOps.get() != 0) ;
    }

    /***
     * Copies entries from srcChunk performing only entries sorting on the fly
     * (delete entries that are removed as well).
     * @param srcChunk -- chunk to copy from
     * @param srcEntryIdx -- start position for copying
     * @param maxCapacity -- max number of entries "this" chunk can contain after copy
     * @return key index of next to the last copied item, NONE if all items were copied
     */
    final int copyPartNoKeys(Chunk<K, V> srcChunk, int srcEntryIdx, int maxCapacity) {

        if (srcEntryIdx == HEAD_NODE) {
            return NONE;
        }

        // use local variables and just set the atomic variables once at the end
        int sortedEntryIndex = entryIndex.get();

        // check that we are not beyond allowed number of entries to copy from source chunk
        int maxIdx = maxCapacity * FIELDS + 1;
        if (sortedEntryIndex >= maxIdx) {
            return srcEntryIdx;
        }
        assert srcEntryIdx <= entries.length - FIELDS;

        // set the next entry index from where we start to copy
        if (sortedEntryIndex != FIRST_ITEM) {
            setEntryFieldInt(sortedEntryIndex - FIELDS, OFFSET.NEXT, sortedEntryIndex);
        } else {
            setEntryFieldInt(HEAD_NODE, OFFSET.NEXT, FIRST_ITEM);
        }

        int entryIndexStart = srcEntryIdx;
        int entryIndexEnd = entryIndexStart - 1;
        int srcPrevEntryIdx = NONE;
        int currSrcValueBlock;
        boolean isFirstInInterval = true;

        while (true) {
            long currSrcValueReference = srcChunk.getValueReference(srcEntryIdx);
            currSrcValueBlock =
                    UnsafeUtils.longToInts(currSrcValueReference)[BLOCK_ID_LENGTH_ARRAY_INDEX] >>> VALUE_BLOCK_SHIFT;
            boolean isValueDeleted = (currSrcValueBlock == INVALID_BLOCK_ID) ||
                    ValueUtils.isValueDeleted(buildValueSlice(currSrcValueReference));
            int entriesToCopy = entryIndexEnd - entryIndexStart + 1;

            // try to find a continuous interval to copy
            // we cannot enlarge interval: if key is removed (value reference is DELETED_VALUE) or
            // if this chunk already has all entries to start with
            if (!isValueDeleted && (sortedEntryIndex + entriesToCopy * FIELDS < maxIdx)) {
                // we can enlarge the interval, if it is otherwise possible:
                // if this is first entry in the interval (we need to copy one entry anyway) OR
                // if (on the source chunk) current entry idx directly follows the previous entry idx
                if (isFirstInInterval || (srcPrevEntryIdx + FIELDS == srcEntryIdx)) {
                    entryIndexEnd++;
                    isFirstInInterval = false;
                    srcPrevEntryIdx = srcEntryIdx;
                    srcEntryIdx = srcChunk.getEntryFieldInt(srcEntryIdx, OFFSET.NEXT);
                    if (srcEntryIdx != NONE) {
                        continue;
                    }

                }
            }

            entriesToCopy = entryIndexEnd - entryIndexStart + 1;
            if (entriesToCopy > 0) {
                for (int i = 0; i < entriesToCopy; ++i) {
                    int offset = i * FIELDS;
                    // next should point to the next item
                    entries[sortedEntryIndex + offset + OFFSET.NEXT.value]
                            = sortedEntryIndex + offset + FIELDS;

                    // copy both the key and the value references and the padding => 5 integers via array copy
                    System.arraycopy(srcChunk.entries,  // source array
                            entryIndexStart + offset + OFFSET.NEXT.value + 1,
                            entries,                        // destination aray
                            sortedEntryIndex + offset + OFFSET.NEXT.value + 1, (FIELDS - 1));
                }

                sortedEntryIndex += entriesToCopy * FIELDS; // update
            }

            if (isValueDeleted) { // if now this is a removed item
                // don't copy it, continue to next item
                srcPrevEntryIdx = srcEntryIdx;
                srcEntryIdx = srcChunk.getEntryFieldInt(srcEntryIdx, OFFSET.NEXT);
            }

            if (srcEntryIdx == NONE || sortedEntryIndex >= maxIdx) {
                break; // if we are done
            }

            // reset and continue
            entryIndexStart = srcEntryIdx;
            entryIndexEnd = entryIndexStart - 1;
            isFirstInInterval = true;

        }

        // next of last item in serial should point to null
        int setIdx = sortedEntryIndex > FIRST_ITEM ? sortedEntryIndex - FIELDS : HEAD_NODE;
        setEntryFieldInt(setIdx, OFFSET.NEXT, NONE);
        // update index and counter
        entryIndex.set(sortedEntryIndex);
        sortedCount.set(sortedEntryIndex / FIELDS);
        statistics.updateInitialSortedCount(sortedCount.get());
        return srcEntryIdx; // if NONE then we finished copying old chunk, else we reached max in new chunk
    }

    /**
     * marks this chunk's next pointer so this chunk is marked as deleted
     *
     * @return the next chunk pointed to once marked (will not change)
     */
    Chunk<K, V> markAndGetNext() {
        // new chunks are ready, we mark frozen chunk's next pointer so it won't change
        // since next pointer can be changed by other split operations we need to do this in a loop - until we succeed
        while (true) {
            // if chunk is marked - that is ok and its next pointer will not be changed anymore
            // return whatever chunk is set as next
            if (next.isMarked()) {
                return next.getReference();
            }
            // otherwise try to mark it
            else {
                // read chunk's current next
                Chunk<K, V> savedNext = next.getReference();

                // try to mark next while keeping the same next chunk - using CAS
                // if we succeeded then the next pointer we remembered is set and will not change - return it
                if (next.compareAndSet(savedNext, savedNext, false, true)) {
                    return savedNext;
                }
            }
        }
    }


    boolean shouldRebalance() {
        // perform actual check only in pre defined percentage of puts
        if (ThreadLocalRandom.current().nextInt(100) > REBALANCE_PROB_PERC) {
            return false;
        }

        // if another thread already runs rebalance -- skip it
        if (!isEngaged(null)) {
            return false;
        }
        int numOfEntries = entryIndex.get() / FIELDS;
        int numOfItems = statistics.getCompactedCount();
        int sortedCount = this.sortedCount.get();
        // Reasons for executing a rebalance:
        // 1. There are no sorted keys and the total number of entries is above a certain threshold.
        // 2. There are sorted keys, but the total number of unsorted keys is too big.
        // 3. Out of the occupied entries, there are not enough actual items.
        return (sortedCount == 0 && numOfEntries * MAX_ENTRIES_FACTOR > maxItems) ||
                (sortedCount > 0 && (sortedCount * SORTED_REBALANCE_RATIO) < numOfEntries) ||
                (numOfEntries * MAX_IDLE_ENTRIES_FACTOR > maxItems && numOfItems * MAX_IDLE_ENTRIES_FACTOR < numOfEntries);
    }

    /*-------------- Iterators --------------*/

    AscendingIter ascendingIter() {
        return new AscendingIter();
    }

    AscendingIter ascendingIter(K from, boolean inclusive) {
        return new AscendingIter(from, inclusive);
    }

    DescendingIter descendingIter() {
        return new DescendingIter();
    }

    DescendingIter descendingIter(K from, boolean inclusive) {
        return new DescendingIter(from, inclusive);
    }

    interface ChunkIter {
        boolean hasNext();

        int next();
    }

    class AscendingIter implements ChunkIter {

        private int next;

        AscendingIter() {
            next = getEntryFieldInt(HEAD_NODE, OFFSET.NEXT);
            int valueBlock = getEntryFieldInt(next, OFFSET.VALUE_BLOCK);
            while (next != Chunk.NONE && valueBlock == INVALID_BLOCK_ID) {
                next = getEntryFieldInt(next, OFFSET.NEXT);
                valueBlock = getEntryFieldInt(next, OFFSET.VALUE_BLOCK);
            }
        }

        AscendingIter(K from, boolean inclusive) {
            next = getEntryFieldInt(binaryFind(from), OFFSET.NEXT);

            int valueBlock = getEntryFieldInt(next, OFFSET.VALUE_BLOCK);

            int compare = -1;
            if (next != Chunk.NONE) {
                compare = comparator.compareKeyAndSerializedKey(from, readKey(next));
            }

            while (next != Chunk.NONE &&
                    (compare > 0 ||
                            (compare >= 0 && !inclusive) || valueBlock == INVALID_BLOCK_ID)) {
                next = getEntryFieldInt(next, OFFSET.NEXT);
                valueBlock = getEntryFieldInt(next, OFFSET.VALUE_BLOCK);
                if (next != Chunk.NONE) {
                    compare = comparator.compareKeyAndSerializedKey(from, readKey(next));
                }
            }
        }

        private void advance() {
            next = getEntryFieldInt(next, OFFSET.NEXT);
            int valueBlock = getEntryFieldInt(next, OFFSET.VALUE_BLOCK);
            while (next != Chunk.NONE && valueBlock == INVALID_BLOCK_ID) {
                next = getEntryFieldInt(next, OFFSET.NEXT);
                valueBlock = getEntryFieldInt(next, OFFSET.VALUE_BLOCK);
            }
        }

        @Override
        public boolean hasNext() {
            return next != Chunk.NONE;
        }

        @Override
        public int next() {
            int toReturn = next;
            advance();
            return toReturn;
        }
    }

    class DescendingIter implements ChunkIter {

        private int next;
        private int anchor;
        private int prevAnchor;
        private final IntStack stack;
        private final K from;
        private boolean inclusive;

        DescendingIter() {
            from = null;
            stack = new IntStack(entries.length / FIELDS);
            int sortedCnt = sortedCount.get();
            anchor = sortedCnt == 0 ? HEAD_NODE : (sortedCnt - 1) * (FIELDS) + 1; // this is the last sorted entry
            stack.push(anchor);
            initNext();
        }

        DescendingIter(K from, boolean inclusive) {
            this.from = from;
            this.inclusive = inclusive;
            stack = new IntStack(entries.length / FIELDS);
            anchor = binaryFind(from);
            stack.push(anchor);
            initNext();
        }

        private void initNext() {
            traverseLinkedList();
            advance();
        }

        /**
         * use stack to find a valid next, removed items can't be next
         */
        private void findNewNextInStack() {
            if (stack.empty()) {
                next = Chunk.NONE;
                return;
            }
            next = stack.pop();
            int valueBlock = getEntryFieldInt(next, OFFSET.VALUE_BLOCK);
            while (next != Chunk.NONE && valueBlock == INVALID_BLOCK_ID) {
                if (!stack.empty()) {
                    next = stack.pop();
                    valueBlock = getEntryFieldInt(next, OFFSET.VALUE_BLOCK);
                } else {
                    next = Chunk.NONE;
                    return;
                }
            }
        }

        /**
         * fill the stack
         */
        private void traverseLinkedList() {
            assert stack.size() == 1; // ancor is in the stack
            if (prevAnchor == getEntryFieldInt(anchor, OFFSET.NEXT)) {
                // there is no next;
                next = Chunk.NONE;
                return;
            }
            next = getEntryFieldInt(anchor, OFFSET.NEXT);
            if (from == null) {
                while (next != Chunk.NONE) {
                    stack.push(next);
                    next = getEntryFieldInt(next, OFFSET.NEXT);
                }
            } else {
                if (inclusive) {
                    while (next != Chunk.NONE && comparator.compareKeyAndSerializedKey(from, readKey(next)) >= 0) {
                        stack.push(next);
                        next = getEntryFieldInt(next, OFFSET.NEXT);
                    }
                } else {
                    while (next != Chunk.NONE && comparator.compareKeyAndSerializedKey(from, readKey(next)) > 0) {
                        stack.push(next);
                        next = getEntryFieldInt(next, OFFSET.NEXT);
                    }
                }
            }
        }

        /**
         * find new valid anchor
         */
        private void findNewAnchor() {
            assert stack.empty();
            prevAnchor = anchor;
            if (anchor == HEAD_NODE) {
                next = Chunk.NONE; // there is no more in this chunk
                return;
            } else if (anchor == FIRST_ITEM) {
                anchor = HEAD_NODE;
            } else {
                anchor = anchor - FIELDS;
            }
            stack.push(anchor);
        }

        private void advance() {
            while (true) {
                findNewNextInStack();
                if (next != Chunk.NONE) {
                    return;
                }
                // there is no next in stack
                if (anchor == HEAD_NODE) {
                    // there is no next at all
                    return;
                }
                findNewAnchor();
                traverseLinkedList();
            }
        }

        @Override
        public boolean hasNext() {
            return next != Chunk.NONE;
        }

        @Override
        public int next() {
            int toReturn = next;
            advance();
            return toReturn;
        }

    }

    /**
     * just a simple stack of int, implemented with int array
     */

    static class IntStack {

        private final int[] stack;
        private int top;

        IntStack(int size) {
            stack = new int[size];
            top = 0;
        }

        void push(int i) {
            if (top == stack.length) {
                throw new ArrayIndexOutOfBoundsException();
            }
            stack[top] = i;
            top++;
        }

        int pop() {
            if (empty()) {
                throw new EmptyStackException();
            }
            top--;
            return stack[top];
        }

        boolean empty() {
            return top == 0;
        }

        int size() {
            return top;
        }

    }

    /*-------------- Statistics --------------*/

    /**
     * This class contains information about chunk utilization.
     */
    static class Statistics {
        private final AtomicInteger addedCount = new AtomicInteger(0);
        private int initialSortedCount = 0;

        /**
         * Initial sorted count here is immutable after chunk re-balance
         */
        void updateInitialSortedCount(int sortedCount) {
            this.initialSortedCount = sortedCount;
        }

        /**
         * @return number of items chunk will contain after compaction.
         */
        int getCompactedCount() {
            return initialSortedCount + getAddedCount();
        }

        /**
         * Incremented when put a key that was removed before
         */
        void incrementAddedCount() {
            addedCount.incrementAndGet();
        }

        /**
         * Decrement when remove a key that was put before
         */
        void decrementAddedCount() {
            addedCount.decrementAndGet();
        }

        int getAddedCount() {
            return addedCount.get();
        }

    }

    /**
     * @return statistics object containing approximate utilization information.
     */
    Statistics getStatistics() {
        return statistics;
    }

}
