package br.com.factory.events.exception;

public class ManualRequestConflictException extends RuntimeException {
    public ManualRequestConflictException() {
        super("Esta tentativa já registrou outro conteúdo. Inicie um novo cadastro.");
    }
}
