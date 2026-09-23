package com.socp.alert.domain;

/** Database projection; no alarm entities are hydrated for technique counts. */
public record AlarmTechniqueCount(String technique, long count) { }
