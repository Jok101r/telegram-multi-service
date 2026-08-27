package com.example.voicebot.session;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserSessionService {

    private final Map<Long, UserState> states = new ConcurrentHashMap<>();
    private final Map<Long, List<Integer>> messageIds = new ConcurrentHashMap<>();

    public UserState getState(Long chatId) {
        return states.getOrDefault(chatId, UserState.IDLE);
    }

    public void setState(Long chatId, UserState state) {
        states.put(chatId, state);
    }

    public void trackMessage(Long chatId, Integer messageId) {
        messageIds.computeIfAbsent(chatId, k -> Collections.synchronizedList(new ArrayList<>())).add(messageId);
    }

    public List<Integer> popMessageIds(Long chatId) {
        List<Integer> ids = messageIds.remove(chatId);
        return ids != null ? new ArrayList<>(ids) : Collections.emptyList();
    }
}
