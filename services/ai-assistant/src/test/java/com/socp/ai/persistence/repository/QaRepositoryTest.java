package com.socp.ai.persistence.repository;


import com.socp.ai.persistence.entity.QaEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class QaRepositoryTest {

    @Autowired
    private QaRepository repository;

    @Test
    void findsTheMostSpecificDatabaseKeyword() {
        repository.save(new QaEntity("credential", "generic"));
        repository.save(new QaEntity("credential exposure", "specific"));

        var matches = repository.findMatches("credential exposure response", PageRequest.of(0, 1));

        assertEquals(1, matches.size());
        assertEquals("specific", matches.getFirst().getAnswer());
    }
}
