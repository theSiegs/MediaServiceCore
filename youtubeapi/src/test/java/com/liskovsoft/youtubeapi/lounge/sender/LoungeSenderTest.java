package com.liskovsoft.youtubeapi.lounge.sender;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LoungeSenderTest {
    @Test
    public void readsTheScreenAPairingCodeGives() {
        LoungeSender.Screen screen = LoungeSender.parseScreen("{\"screen\":{\"accessType\":\"permanent\","
                + "\"screenId\":\"abc123\",\"loungeToken\":\"tok\",\"name\":\"Living room TV\"}}");
        assertEquals("abc123", screen.screenId);
        assertEquals("tok", screen.loungeToken);
        assertEquals("Living room TV", screen.name);
    }

    @Test
    public void noScreenForAWrongCode() {
        assertNull(LoungeSender.parseScreen("{}"));
        assertNull(LoungeSender.parseScreen("not json"));
    }

    @Test
    public void readsARenewedToken() {
        String json = "{\"screens\":[{\"screenId\":\"other\",\"loungeToken\":\"x\"},"
                + "{\"screenId\":\"abc123\",\"loungeToken\":\"fresh\",\"expiration\":1}]}";
        assertEquals("fresh", LoungeSender.parseToken(json, "abc123"));
        assertNull(LoungeSender.parseToken(json, "gone"));
    }

    @Test
    public void readsTheSessionFromABindAnswer() {
        String bound = "147\n[[0,[\"c\",\"SID123\",\"\",8]]\n,[1,[\"S\",\"GSESS456\"]]\n,[2,[\"loungeStatus\",{}]]\n]\n";
        assertArrayEquals(new String[] {"SID123", "GSESS456"}, LoungeSender.parseSession(bound));
        assertNull(LoungeSender.parseSession("[[0,[\"noop\"]]]"));
    }
}
