package org.example.message.service.send;
/** A sending contract. Each implementation owns its permission checks, transaction and post-commit delivery. */
public interface MessageSender<C,R> { R send(C command); }
