package com.socp.alert.persistence.repository;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmQuery;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;


/** Database-side filtering and deterministic sorting for alarm reads. */
public interface AlarmRepositoryCustom {

    record Creation(Alarm alarm, boolean created) { }

    /** A competing tenant/source identity returns the committed winner without side effects. */
    Creation insertIfAbsent(Alarm alarm);

    Page<Alarm> page(String tenant, AlarmQuery query, Pageable pageable);

    /** Count a filtered tenant view without materialising the matching alarms. */
    long count(String tenant, AlarmQuery query);

}
