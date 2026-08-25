package com.socp.alert.domain;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

public enum AlarmDeliveryDestination {
    CLICKHOUSE,
    NOTIFY,
    INCIDENT,
    SOAR
}
