package com.socp.search.config.render;

import com.socp.search.config.domain.SinkTarget;

/** Resolves the durable output target of one log source during Vector rendering. */
@FunctionalInterface
public interface SinkResolver {

    /**
     * @param sinkTargetId the source's binding; {@code null}/blank means "no explicit binding"
     * @return the platform or tenant target, or {@code null} when nothing is bound
     */
    SinkTarget resolve(String sinkTargetId);

    /** Resolver with no configured targets: every active source fails rendering with 409. */
    SinkResolver NONE = sinkTargetId -> null;
}
