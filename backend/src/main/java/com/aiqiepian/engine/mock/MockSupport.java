package com.aiqiepian.engine.mock;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** mock 引擎通用随机工具（对照 server.mjs 的 rnd/pick） */
public final class MockSupport {

    private MockSupport() {
    }

    /** 闭区间随机整数 */
    public static int rnd(int a, int b) {
        return ThreadLocalRandom.current().nextInt(a, b + 1);
    }

    public static boolean chance(double p) {
        return ThreadLocalRandom.current().nextDouble() < p;
    }

    public static <T> T pick(List<T> arr) {
        return arr.get(rnd(0, arr.size() - 1));
    }
}
