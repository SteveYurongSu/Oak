package oak;

import java.nio.*;

public abstract class WritableOakBuffer extends OakBuffer {

    /**
     * Sets this buffer's position.  If the mark is defined and larger than the
     * new position then it is discarded.
     *
     * @param newPosition The new position value; must be non-negative
     *                    and no larger than the current limit
     * @return This buffer
     * @throws IllegalArgumentException If the preconditions on <tt>newPosition</tt> do not hold
     */
    public abstract WritableOakBuffer position(int newPosition);

    /**
     * Sets this buffer's mark at its position.
     *
     * @return This buffer
     */
    public abstract WritableOakBuffer mark();

    /**
     * Resets this buffer's position to the previously-marked position.
     * <p>
     * <p> Invoking this method neither changes nor discards the mark's
     * value. </p>
     *
     * @return This buffer
     * @throws InvalidMarkException If the mark has not been set
     */
    public abstract WritableOakBuffer reset();

    /**
     * Clears this buffer.  The position is set to zero, the limit is set to
     * the capacity, and the mark is discarded.
     * <p>
     * <p> Invoke this method before using a sequence of channel-read or
     * <i>put</i> operations to fill this buffer.  For example:
     * <p>
     * <blockquote><pre>
     * buf.clear();     // Prepare buffer for reading
     * in.read(buf);    // Read data</pre></blockquote>
     * <p>
     * <p> This method does not actually erase the data in the buffer, but it
     * is named as if it did because it will most often be used in situations
     * in which that might as well be the case. </p>
     *
     * @return This buffer
     */
    public abstract WritableOakBuffer clear();

    /**
     * Flips this buffer.  The limit is set to the current position and then
     * the position is set to zero.  If the mark is defined then it is
     * discarded.
     * <p>
     * <p> After a sequence of channel-read or <i>put</i> operations, invoke
     * this method to prepare for a sequence of channel-write or relative
     * <i>get</i> operations.  For example:
     * <p>
     * <blockquote><pre>
     * buf.put(magic);    // Prepend header
     * in.read(buf);      // Read data into rest of buffer
     * buf.flip();        // Flip buffer
     * out.write(buf);    // Write header + data to channel</pre></blockquote>
     * <p>
     * <p> This method is often used in conjunction with the {@link
     * java.nio.ByteBuffer#compact compact} method when transferring data from
     * one place to another.  </p>
     *
     * @return This buffer
     */
    public abstract WritableOakBuffer flip();

    /**
     * Rewinds this buffer.  The position is set to zero and the mark is
     * discarded.
     * <p>
     * <p> Invoke this method before a sequence of channel-write or <i>get</i>
     * operations, assuming that the limit has already been set
     * appropriately.  For example:
     * <p>
     * <blockquote><pre>
     * out.write(buf);    // Write remaining data
     * buf.rewind();      // Rewind buffer
     * buf.get(array);    // Copy data into array</pre></blockquote>
     *
     * @return This buffer
     */
    public abstract WritableOakBuffer rewind();

    /**
     *
     * @return the actual ByteBuffer
     */
    public abstract ByteBuffer getByteBuffer();

    // -- Singleton get/put methods --

    /**
     * Relative <i>get</i> method.  Reads the byte at this buffer's
     * current position, and then increments the position.
     *
     * @return The byte at the buffer's current position
     * @throws BufferUnderflowException If the buffer's current position is not smaller than its limit
     */
    public abstract byte get();

    /**
     * Relative <i>put</i> method&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes the given byte into this buffer at the current
     * position, and then increments the position. </p>
     *
     * @param b The byte to be written
     * @return This buffer
     * @throws BufferOverflowException If this buffer's current position is not smaller than its limit
     * @throws ReadOnlyBufferException If this buffer is read-only
     */
    public abstract WritableOakBuffer put(byte b);

    /**
     * Absolute <i>put</i> method&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes the given byte into this buffer at the given
     * index. </p>
     *
     * @param index The index at which the byte will be written
     * @param b     The byte value to be written
     * @return This buffer
     * @throws IndexOutOfBoundsException If <tt>index</tt> is negative
     *                                   or not smaller than the buffer's limit
     * @throws ReadOnlyBufferException   If this buffer is read-only
     */
    public abstract WritableOakBuffer put(int index, byte b);

    // -- Bulk get operations --

    /**
     * Relative bulk <i>get</i> method.
     * <p>
     * <p> This method transfers bytes from this buffer into the given
     * destination array.  If there are fewer bytes remaining in the
     * buffer than are required to satisfy the request, that is, if
     * <tt>length</tt>&nbsp;<tt>&gt;</tt>&nbsp;<tt>remaining()</tt>, then no
     * bytes are transferred and a {@link BufferUnderflowException} is
     * thrown.
     * <p>
     * <p> Otherwise, this method copies <tt>length</tt> bytes from this
     * buffer into the given array, starting at the current position of this
     * buffer and at the given offset in the array.  The position of this
     * buffer is then incremented by <tt>length</tt>.
     * <p>
     * <p> In other words, an invocation of this method of the form
     * <tt>src.get(dst,&nbsp;off,&nbsp;len)</tt> has exactly the same effect as
     * the loop
     * <p>
     * <pre>{@code
     *     for (int i = off; i < off + len; i++)
     *         dst[i] = src.get():
     * }</pre>
     * <p>
     * except that it first checks that there are sufficient bytes in
     * this buffer and it is potentially much more efficient.
     *
     * @param dst    The array into which bytes are to be written
     * @param offset The offset within the array of the first byte to be
     *               written; must be non-negative and no larger than
     *               <tt>dst.length</tt>
     * @param length The maximum number of bytes to be written to the given
     *               array; must be non-negative and no larger than
     *               <tt>dst.length - offset</tt>
     * @return This buffer
     * @throws BufferUnderflowException  If there are fewer than <tt>length</tt> bytes
     *                                   remaining in this buffer
     * @throws IndexOutOfBoundsException If the preconditions on the <tt>offset</tt> and <tt>length</tt>
     *                                   parameters do not hold
     */
    public WritableOakBuffer get(byte[] dst, int offset, int length) {
        return null;
    }

    /**
     * Relative bulk <i>get</i> method.
     * <p>
     * <p> This method transfers bytes from this buffer into the given
     * destination array.  An invocation of this method of the form
     * <tt>src.get(a)</tt> behaves in exactly the same way as the invocation
     * <p>
     * <pre>
     *     src.get(a, 0, a.length) </pre>
     *
     * @param dst The destination array
     * @return This buffer
     * @throws BufferUnderflowException If there are fewer than <tt>length</tt> bytes
     *                                  remaining in this buffer
     */
    public WritableOakBuffer get(byte[] dst) {
        return get(dst, 0, dst.length);
    }

    // -- Bulk put operations --

    /**
     * Relative bulk <i>put</i> method&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> This method transfers the bytes remaining in the given source
     * buffer into this buffer.  If there are more bytes remaining in the
     * source buffer than in this buffer, that is, if
     * <tt>src.remaining()</tt>&nbsp;<tt>&gt;</tt>&nbsp;<tt>remaining()</tt>,
     * then no bytes are transferred and a {@link
     * BufferOverflowException} is thrown.
     * <p>
     * <p> Otherwise, this method copies
     * <i>n</i>&nbsp;=&nbsp;<tt>src.remaining()</tt> bytes from the given
     * buffer into this buffer, starting at each buffer's current position.
     * The positions of both buffers are then incremented by <i>n</i>.
     * <p>
     * <p> In other words, an invocation of this method of the form
     * <tt>dst.put(src)</tt> has exactly the same effect as the loop
     * <p>
     * <pre>
     *     while (src.hasRemaining())
     *         dst.put(src.get()); </pre>
     * <p>
     * except that it first checks that there is sufficient space in this
     * buffer and it is potentially much more efficient.
     *
     * @param src The source buffer from which bytes are to be read;
     *            must not be this buffer
     * @return This buffer
     * @throws BufferOverflowException  If there is insufficient space in this buffer
     *                                  for the remaining bytes in the source buffer
     * @throws IllegalArgumentException If the source buffer is this buffer
     * @throws ReadOnlyBufferException  If this buffer is read-only
     */
    public abstract WritableOakBuffer put(byte[] src, int offset, int length);

    /**
     * Relative bulk <i>put</i> method&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> This method transfers the entire content of the given source
     * byte array into this buffer.  An invocation of this method of the
     * form <tt>dst.put(a)</tt> behaves in exactly the same way as the
     * invocation
     * <p>
     * <pre>
     *     dst.put(a, 0, a.length) </pre>
     *
     * @param src The source array
     * @return This buffer
     * @throws BufferOverflowException If there is insufficient space in this buffer
     * @throws ReadOnlyBufferException If this buffer is read-only
     */
    public WritableOakBuffer put(byte[] src) {
        return put(src, 0, src.length);
    }

    /**
     * Modifies this buffer's byte order.
     *
     * @param bo The new byte order,
     *           either {@link ByteOrder#BIG_ENDIAN BIG_ENDIAN}
     *           or {@link ByteOrder#LITTLE_ENDIAN LITTLE_ENDIAN}
     * @return This buffer
     */
    public abstract WritableOakBuffer order(ByteOrder bo);

    /**
     * Relative <i>get</i> method for reading a char value.
     * <p>
     * <p> Reads the next two bytes at this buffer's current position,
     * composing them into a char value according to the current byte order,
     * and then increments the position by two.  </p>
     *
     * @return The char value at the buffer's current position
     * @throws BufferUnderflowException If there are fewer than two bytes
     *                                  remaining in this buffer
     */
    public abstract char getChar();

    /**
     * Relative <i>put</i> method for writing a char
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes two bytes containing the given char value, in the
     * current byte order, into this buffer at the current position, and then
     * increments the position by two.  </p>
     *
     * @param value The char value to be written
     * @return This buffer
     * @throws BufferOverflowException If there are fewer than two bytes
     *                                 remaining in this buffer
     * @throws ReadOnlyBufferException If this buffer is read-only
     */
    public abstract WritableOakBuffer putChar(char value);

    /**
     * Absolute <i>put</i> method for writing a char
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes two bytes containing the given char value, in the
     * current byte order, into this buffer at the given index.  </p>
     *
     * @param index The index at which the bytes will be written
     * @param value The char value to be written
     * @return This buffer
     * @throws IndexOutOfBoundsException If <tt>index</tt> is negative
     *                                   or not smaller than the buffer's limit,
     *                                   minus one
     * @throws ReadOnlyBufferException   If this buffer is read-only
     */
    public abstract WritableOakBuffer putChar(int index, char value);

    /**
     * Relative <i>get</i> method for reading a short value.
     * <p>
     * <p> Reads the next two bytes at this buffer's current position,
     * composing them into a short value according to the current byte order,
     * and then increments the position by two.  </p>
     *
     * @return The short value at the buffer's current position
     * @throws BufferUnderflowException If there are fewer than two bytes
     *                                  remaining in this buffer
     */
    public abstract short getShort();

    /**
     * Relative <i>put</i> method for writing a short
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes two bytes containing the given short value, in the
     * current byte order, into this buffer at the current position, and then
     * increments the position by two.  </p>
     *
     * @param value The short value to be written
     * @return This buffer
     * @throws BufferOverflowException If there are fewer than two bytes
     *                                 remaining in this buffer
     * @throws ReadOnlyBufferException If this buffer is read-only
     */
    public abstract WritableOakBuffer putShort(short value);

    /**
     * Absolute <i>put</i> method for writing a short
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes two bytes containing the given short value, in the
     * current byte order, into this buffer at the given index.  </p>
     *
     * @param index The index at which the bytes will be written
     * @param value The short value to be written
     * @return This buffer
     * @throws IndexOutOfBoundsException If <tt>index</tt> is negative
     *                                   or not smaller than the buffer's limit,
     *                                   minus one
     * @throws ReadOnlyBufferException   If this buffer is read-only
     */
    public abstract WritableOakBuffer putShort(int index, short value);

    /**
     * Relative <i>get</i> method for reading an int value.
     * <p>
     * <p> Reads the next four bytes at this buffer's current position,
     * composing them into an int value according to the current byte order,
     * and then increments the position by four.  </p>
     *
     * @return The int value at the buffer's current position
     * @throws BufferUnderflowException If there are fewer than four bytes
     *                                  remaining in this buffer
     */
    public abstract int getInt();

    /**
     * Relative <i>put</i> method for writing an int
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes four bytes containing the given int value, in the
     * current byte order, into this buffer at the current position, and then
     * increments the position by four.  </p>
     *
     * @param value The int value to be written
     * @return This buffer
     * @throws BufferOverflowException If there are fewer than four bytes
     *                                 remaining in this buffer
     * @throws ReadOnlyBufferException If this buffer is read-only
     */
    public abstract WritableOakBuffer putInt(int value);

    /**
     * Absolute <i>put</i> method for writing an int
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes four bytes containing the given int value, in the
     * current byte order, into this buffer at the given index.  </p>
     *
     * @param index The index at which the bytes will be written
     * @param value The int value to be written
     * @return This buffer
     * @throws IndexOutOfBoundsException If <tt>index</tt> is negative
     *                                   or not smaller than the buffer's limit,
     *                                   minus three
     * @throws ReadOnlyBufferException   If this buffer is read-only
     */
    public abstract WritableOakBuffer putInt(int index, int value);

    /**
     * Relative <i>get</i> method for reading a long value.
     * <p>
     * <p> Reads the next eight bytes at this buffer's current position,
     * composing them into a long value according to the current byte order,
     * and then increments the position by eight.  </p>
     *
     * @return The long value at the buffer's current position
     * @throws BufferUnderflowException If there are fewer than eight bytes
     *                                  remaining in this buffer
     */
    public abstract long getLong();

    /**
     * Relative <i>put</i> method for writing a long
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes eight bytes containing the given long value, in the
     * current byte order, into this buffer at the current position, and then
     * increments the position by eight.  </p>
     *
     * @param value The long value to be written
     * @return This buffer
     * @throws BufferOverflowException If there are fewer than eight bytes
     *                                 remaining in this buffer
     * @throws ReadOnlyBufferException If this buffer is read-only
     */
    public abstract WritableOakBuffer putLong(long value);

    /**
     * Absolute <i>put</i> method for writing a long
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes eight bytes containing the given long value, in the
     * current byte order, into this buffer at the given index.  </p>
     *
     * @param index The index at which the bytes will be written
     * @param value The long value to be written
     * @return This buffer
     * @throws IndexOutOfBoundsException If <tt>index</tt> is negative
     *                                   or not smaller than the buffer's limit,
     *                                   minus seven
     * @throws ReadOnlyBufferException   If this buffer is read-only
     */
    public abstract WritableOakBuffer putLong(int index, long value);

    /**
     * Relative <i>get</i> method for reading a float value.
     * <p>
     * <p> Reads the next four bytes at this buffer's current position,
     * composing them into a float value according to the current byte order,
     * and then increments the position by four.  </p>
     *
     * @return The float value at the buffer's current position
     * @throws BufferUnderflowException If there are fewer than four bytes
     *                                  remaining in this buffer
     */
    public abstract float getFloat();

    /**
     * Relative <i>put</i> method for writing a float
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes four bytes containing the given float value, in the
     * current byte order, into this buffer at the current position, and then
     * increments the position by four.  </p>
     *
     * @param value The float value to be written
     * @return This buffer
     * @throws BufferOverflowException If there are fewer than four bytes
     *                                 remaining in this buffer
     * @throws ReadOnlyBufferException If this buffer is read-only
     */
    public abstract WritableOakBuffer putFloat(float value);

    /**
     * Absolute <i>put</i> method for writing a float
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes four bytes containing the given float value, in the
     * current byte order, into this buffer at the given index.  </p>
     *
     * @param index The index at which the bytes will be written
     * @param value The float value to be written
     * @return This buffer
     * @throws IndexOutOfBoundsException If <tt>index</tt> is negative
     *                                   or not smaller than the buffer's limit,
     *                                   minus three
     * @throws ReadOnlyBufferException   If this buffer is read-only
     */
    public abstract WritableOakBuffer putFloat(int index, float value);

    /**
     * Relative <i>get</i> method for reading a double value.
     * <p>
     * <p> Reads the next eight bytes at this buffer's current position,
     * composing them into a double value according to the current byte order,
     * and then increments the position by eight.  </p>
     *
     * @return The double value at the buffer's current position
     * @throws BufferUnderflowException If there are fewer than eight bytes
     *                                  remaining in this buffer
     */
    public abstract double getDouble();

    /**
     * Relative <i>put</i> method for writing a double
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes eight bytes containing the given double value, in the
     * current byte order, into this buffer at the current position, and then
     * increments the position by eight.  </p>
     *
     * @param value The double value to be written
     * @return This buffer
     * @throws BufferOverflowException If there are fewer than eight bytes
     *                                 remaining in this buffer
     * @throws ReadOnlyBufferException If this buffer is read-only
     */
    public abstract WritableOakBuffer putDouble(double value);

    /**
     * Absolute <i>put</i> method for writing a double
     * value&nbsp;&nbsp;<i>(optional operation)</i>.
     * <p>
     * <p> Writes eight bytes containing the given double value, in the
     * current byte order, into this buffer at the given index.  </p>
     *
     * @param index The index at which the bytes will be written
     * @param value The double value to be written
     * @return This buffer
     * @throws IndexOutOfBoundsException If <tt>index</tt> is negative
     *                                   or not smaller than the buffer's limit,
     *                                   minus seven
     * @throws ReadOnlyBufferException   If this buffer is read-only
     */
    public abstract WritableOakBuffer putDouble(int index, double value);
}
