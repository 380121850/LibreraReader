package com.foobnix.autotest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.foobnix.android.utils.MyMath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 单元层（JVM, CI runner）：MyMath 纯逻辑。
 * 依赖 app 的 LOG 框架（内部读 Build.*，纯 JVM 无值），故以 Robolectric 提供 Build 值运行。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30}, application = android.app.Application.class)
public class MyMathTest {

    @Test
    public void percent_half() {
        assertEquals(0.5f, MyMath.percent(1, 2), 0.0001f);
    }

    @Test
    public void percent_quarter() {
        assertEquals(0.25f, MyMath.percent(1, 4), 0.0001f);
    }

    @Test
    public void percent_full() {
        assertEquals(1.0f, MyMath.percent(10, 10), 0.0001f);
    }

    @Test
    public void percent_zeroPage() {
        assertEquals(0.0f, MyMath.percent(0, 10), 0.0001f);
    }

    @Test
    public void longValue_valid() {
        assertEquals(42L, MyMath.longValueOfNoException("42"));
    }

    @Test
    public void longValue_negative() {
        assertEquals(-7L, MyMath.longValueOfNoException("-7"));
    }

    @Test
    public void longValue_garbage_returnsZero() {
        assertEquals(0L, MyMath.longValueOfNoException("abc"));
    }

    @Test
    public void longValue_empty_returnsZero() {
        assertEquals(0L, MyMath.longValueOfNoException(""));
    }

    @Test
    public void longValue_null_returnsZero() {
        assertEquals(0L, MyMath.longValueOfNoException(null));
    }

    @Test
    public void longValue_largeValue() {
        assertTrue(MyMath.longValueOfNoException("9999999999") > 0);
    }
}
