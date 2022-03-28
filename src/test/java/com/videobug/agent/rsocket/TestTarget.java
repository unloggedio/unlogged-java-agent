package com.videobug.agent.rsocket;

public class TestTarget implements Runnable {
    public int gcdOfTwoNumbers(int n1, int n2) {
        while (n1 != n2 && n1 > 0 && n2 > 0) {
            if (n1 > n2)
                n1 = n1 - n2;
            else
                n2 = n2 - n1;
        }

        String s1 = "hello world";
        while (s1.length() > 5) {
            s1 = s1.substring(1);
        }

        return n2;
    }

    @Override
    public void run() {
        System.out.println("Calculate GCD of 1082 and 134");
        int x = gcdOfTwoNumbers(1082, 134);
        System.out.println("Calculate GCD of 1082 and 134 = " + x);
    }
}
