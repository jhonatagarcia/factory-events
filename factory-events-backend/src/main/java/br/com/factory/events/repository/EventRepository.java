package br.com.factory.events.repository;

import br.com.factory.events.entity.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository
    extends JpaRepository<EventEntity, String> {
}
