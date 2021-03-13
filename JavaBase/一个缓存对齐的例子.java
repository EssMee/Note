package com.algorithm.zy.msb;

public class T02_CacheLinePadding {
    private static class Padding {
        private volatile long p1,p2,p3,p4,p5,p6,p7;
    }
    /*T继承自Padding，那么T的对象也会获得56个字节的填充，即使不用*/
    private static class T extends Padding {
        private volatile long x = 0L;
    }
    public static T[] arr = new T[2];
    static {
        arr[0] = new T();
        arr[1] = new T();
    }
    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(() -> {
            for (long i = 0; i < 1000_0000L; i ++) {
                arr[0].x = i;
            }
        });
        Thread t2 = new Thread(() -> {
            for (long i = 0; i < 1000_0000L; i++) {
                arr[1].x = i;
            }
        });
        final long start = System.nanoTime();
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        System.out.println(((System.nanoTime()) - start )/ 100_0000);
    }
}
