package com.tailtopia.healthreminder.service;

import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 到访行写入（独立 bean 上的 REQUIRES_NEW，写库监听纪律）。 */
@Service
public class VetConsultVisitWriter {

    private final VetConsultVisitDao dao;

    public VetConsultVisitWriter(VetConsultVisitDao dao) {
        this.dao = dao;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(long sessionId, long petId, LocalDate fallbackDate) {
        return dao.record(sessionId, petId, fallbackDate);
    }
}
