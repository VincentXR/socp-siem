package com.socp.detect.web.persistence.repository;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** Bounded owner-row locking with the same lexical order as checkpoint writes. */
public interface DetectionStateOwnerLocks {
    @Transactional(propagation = Propagation.MANDATORY)
    List<DetectionStateOwnerRepository.LockedFence> lockFences(Collection<String> ownerKeys);
}
