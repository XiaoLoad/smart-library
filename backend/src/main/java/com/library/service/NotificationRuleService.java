package com.library.service;

import com.library.entity.NotificationRule;
import com.library.mapper.NotificationRuleMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotificationRuleService {

    private final NotificationRuleMapper ruleMapper;

    public NotificationRuleService(NotificationRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    public List<NotificationRule> listAll() {
        return ruleMapper.selectAll();
    }

    public List<NotificationRule> listEnabled() {
        return ruleMapper.selectAll().stream()
                .filter(r -> r.getEnabled() != null && r.getEnabled() == 1)
                .collect(Collectors.toList());
    }

    public void saveAll(List<NotificationRule> rules) {
        if (rules == null) return;
        for (NotificationRule r : rules) {
            ruleMapper.upsert(r);
        }
    }
}
