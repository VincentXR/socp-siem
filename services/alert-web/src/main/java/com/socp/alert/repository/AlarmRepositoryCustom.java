package com.socp.alert.repository;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** Database-side filtering and deterministic sorting for alarm reads. */
public interface AlarmRepositoryCustom {

    Page<Alarm> page(String tenant, AlarmQuery query, Pageable pageable);

    List<Alarm> list(String tenant, AlarmQuery query);
}
