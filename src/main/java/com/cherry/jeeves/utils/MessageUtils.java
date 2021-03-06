package com.cherry.jeeves.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageUtils {
    public static String getChatRoomTextMessageContent(String content) {
        if (content == null) {
            return null;
        }
        return content.replaceAll("^(@([0-9]|[a-z])+):", "").replaceFirst("<br/>", "");
    }

    public static String getSenderOfChatRoomTextMessage(String content) {
        if (content == null) {
            throw new IllegalArgumentException("content");
        }
        Pattern pattern = Pattern.compile("^(@([0-9]|[a-z])+):");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
