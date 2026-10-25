package br.com.factory.events.exception;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException() {
        super("Evento não encontrado.");
    }
}
