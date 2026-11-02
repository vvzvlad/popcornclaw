package com.omarchytv.home;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WeatherTest {
    @Test
    public void celsiusUnchangedFahrenheitConverted() {
        assertEquals(13, Weather.temp(12.6, false));
        assertEquals(-3, Weather.temp(-2.6, false));
        assertEquals(32, Weather.temp(0, true));
        assertEquals(212, Weather.temp(100, true));
        assertEquals(55, Weather.temp(12.6, true));
    }
}
