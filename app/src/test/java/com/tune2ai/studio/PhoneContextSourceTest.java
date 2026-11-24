package com.tune2ai.studio;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class PhoneContextSourceTest {
    @Test
    public void searchTermsKeepNamesAndContentWords() {
        assertEquals(Arrays.asList("hdfc", "otp"), PhoneContextSource.searchTerms("what is my hdfc otp"));
        assertEquals(Collections.singletonList("ravi"), PhoneContextSource.searchTerms("what did ravi send me?"));
        assertEquals(Arrays.asList("amazon", "delivery"), PhoneContextSource.searchTerms("any sms from amazon about delivery"));
    }

    @Test
    public void genericQuestionsHaveNoTerms() {
        assertEquals(Collections.emptyList(), PhoneContextSource.searchTerms("show my latest messages"));
        assertEquals(Collections.emptyList(), PhoneContextSource.searchTerms("read my sms"));
    }
}
