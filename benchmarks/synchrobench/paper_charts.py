import math
import subprocess
import sys

JAR_PATH =  './target/oak-benchmarks-synchrobench-0.1.6-SNAPSHOT.jar'
output_dir = './output'

def scan_put_run(heap, data, threads):


    print('running scan_put test')

    data_range = data*2
    maps = ['com.oath.oak.OakMyBufferMap']
    maps_output = {}
    for ma in maps:
        throughputs = []
        if ma == 'JavaSkipListMap':
            onheap = heap
            offheap = 1
        else:
            offheap = math.ceil(((data * (1000 + 100))/1000000000)*1.3)
            onheap = heap - offheap

        cmd = f'java -server -Xmx{str(onheap)}g -XX:MaxDirectMemorySize={str(offheap)}g -jar {JAR_PATH} -b {ma} -k 100 -v 1000 -i {str(data)} -r {str(data_range)} -n 1 -t {str(threads)} -d 60000 -W 0 -u 5 -a 0 -s 0 --buffer'

        print(cmd)

        for i in range(3):
            print(f'iteration {str(i)}')
            proc = subprocess.run(cmd.split(' '),encoding='utf-8', stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if 'Exception' in proc.stdout or not list(
                filter(
                    lambda x: 'Throughput (ops/s)' in x,
                    proc.stdout.split('\n'),
                )
            ):
                print('Error')
                print(proc.stdout)
                print(proc.stderr)
                continue
            throughput_line = list(filter(lambda x: 'Throughput (ops/s)' in x, proc.stdout.split('\n')))
            throughput = float(throughput_line[0].split('\t')[1])
            print(throughput)
            throughputs.append(throughput)

        median = 0 if len(throughputs) != 3 else 1
        print(median)
        maps_output[ma] = median
    return maps_output


def ingestion_run(heap, data, offheap=''):
    if offheap == '':
        offheap = math.ceil(((data * (1000 + 100))/(1024*1024*1024))*1.1)

    onheap = heap - offheap


    print('running ingestion test')

    data_range = data*2
    maps = ['com.oath.oak.OakMyBufferMap']
    maps_output = {}

    for ma in maps:
        ingestion_times = []
        if ma == 'JavaSkipListMap':
            cmd = f'java -server -Xmx{str(heap)}g -XX:MaxDirectMemorySize=1g -jar {JAR_PATH} -b {ma} -k 100 -v 1000 -i {str(data)} -r {str(data_range)} -n 1 -t 01 -d 1000 -W 0'

        else:
            cmd = f'java -server -Xmx{str(onheap)}g -XX:MaxDirectMemorySize={str(offheap)}g -jar {JAR_PATH} -b {ma} -k 100 -v 1000 -i {str(data)} -r {str(data_range)} -n 1 -t 01 -d 10 -W 0'

        print(cmd)

        for i in range(3):
            print(f'iteration {str(i)}')
            proc = subprocess.run(cmd.split(' '),encoding='utf-8', stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if 'Exception' in proc.stdout or not list(
                filter(
                    lambda x: 'Initialization complete in (s)' in x,
                    proc.stdout.split('\n'),
                )
            ):
                print('Error')
                print(proc.stdout)
                print(proc.stderr)
                continue
            timeline = list(filter(lambda x: 'Initialization complete in (s)' in x, proc.stdout.split('\n')))[0]
            seconds = timeline.split(' ')[4]
            operations = timeline.split(' ')[6]
            ingestion_times.append(float(operations)/float(seconds))
            print(timeline)

        median = 0 if len(ingestion_times) != 3 else 1
        print(median)
        maps_output[ma] = median
    return maps_output



def rev_ingestion():

    output = {
        heap: ingestion_run(heap, 10000000, offheap)
        for heap, offheap in [(15, 11)]
    }


def ingestion():
    heap=32
    first_data = 5000
    output = {
        data: ingestion_run(heap, data)
        for data in [1000000, 5000000, 10000000, 15000000, 20000000, 25000000]
    }

def scan_put():
    heap = 32
    data = 10000000
    output = {
        threads: scan_put_run(heap, data, threads)
        for threads in [1, 2, 4, 8, 12]
    }

def main():

    bench_map = {'ingestion': ingestion,
                 'scan_put': scan_put,
                 'rev_ingestion': rev_ingestion}

    for bench in sys.argv[1:]:
        bench_map[bench]()
    # ingestion()
    # scan_put()



if __name__ == "__main__":
    # execute only if run as a script
    main()