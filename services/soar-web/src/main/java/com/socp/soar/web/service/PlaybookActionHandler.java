package com.socp.soar.web.service;

import com.socp.soar.web.model.PlaybookActionType;

import java.util.Map;

/** Executes exactly one typed SOAR action family. */
public interface PlaybookActionHandler {

    PlaybookActionType type();

    Map<String, Object> handle(PlaybookActionContext context);
}
