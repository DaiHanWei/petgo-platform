package com.petgo.triage.service;

import com.petgo.shared.media.PersonalMedia;
import com.petgo.triage.domain.TriageTask;
import com.petgo.triage.repository.TriageTaskRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * triage 模块注销级联删除（Story 7.3，决策 D1）：分诊任务为纯个人 AI 健康记录 → <b>物理删除</b>
 * （含私密桶分诊图）。经本 service 接口供 account 编排。
 */
@Service
public class TriageDeletionService {

    private final TriageTaskRepository tasks;

    public TriageDeletionService(TriageTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Transactional
    public PersonalMedia deleteByUserId(long userId) {
        List<String> privateKeys = new ArrayList<>();
        for (TriageTask t : tasks.findByUserId(userId)) {
            if (t.getImageObjectKeys() != null) {
                privateKeys.addAll(t.getImageObjectKeys());
            }
        }
        tasks.deleteByUserId(userId);
        return PersonalMedia.ofPrivate(privateKeys);
    }
}
