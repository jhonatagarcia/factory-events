package br.com.factory.events.repository;

import br.com.factory.events.entity.EventEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

@Repository
public class EventInsertRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public void insert(EventEntity event) {
        entityManager.persist(event);
        entityManager.flush();
    }
}
