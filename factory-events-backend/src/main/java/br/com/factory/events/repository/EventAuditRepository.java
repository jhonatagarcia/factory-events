package br.com.factory.events.repository;

import br.com.factory.events.entity.EventAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventAuditRepository
        extends JpaRepository<EventAuditEntity, Long> {
}
