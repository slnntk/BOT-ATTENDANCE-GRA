package com.gra.paradise.botattendance.service;

import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec;
import discord4j.core.spec.InteractionFollowupCreateSpec;
import discord4j.core.spec.InteractionReplyEditSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    /**
     * Cria uma mensagem de followup temporária com um botão "Ignorar" que o usuário pode clicar
     * Note: Ephemeral messages can't be automatically deleted from Discord's API
     */
    public Mono<Void> sendTemporaryFollowupMessage(ButtonInteractionEvent event, String content, int seconds) {
        return event.createFollowup()
                .withEphemeral(true)
                .withContent(content + "\nEsta mensagem desaparecerá em " + seconds + " segundos.")
                .flatMap(message -> {
                    // Log que a mensagem foi enviada
                    log.debug("Mensagem temporária enviada: {}", message.getId().asString());

                    // Schedule dismissal advice after half the timeout
                    return Mono.delay(Duration.ofSeconds(seconds/2))
                            .then(Mono.fromRunnable(() -> {
                                try {
                                    // Update to add dismissal advice
                                    message.edit()
                                            .withContentOrNull(content + "\n\nSó você pode ver esta • Ignorar mensagem")
                                            .subscribe();
                                } catch (Exception e) {
                                    log.warn("Erro ao atualizar mensagem temporária: {}", e.getMessage());
                                }
                            }))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then();
                });
    }

    /**
     * Versão simplificada que usa 10 segundos como tempo padrão para followups
     */
    public Mono<Void> sendTemporaryFollowupMessage(ButtonInteractionEvent event, String content) {
        return sendTemporaryFollowupMessage(event, content, 10);
    }

    /**
     * Envia resposta inicial como ephemeral
     * Note: For Discord's slash commands, ephemeral messages are managed by Discord and
     * can't be programmatically deleted
     */
    public Mono<Void> sendTemporaryInitialResponse(ButtonInteractionEvent event, String content, int seconds) {
        return event.reply()
                .withEphemeral(true)
                .withContent(content + "\nEsta mensagem desaparecerá em " + seconds + " segundos.")
                .then(Mono.delay(Duration.ofSeconds(seconds/2))
                        .then(event.editReply()
                                .withContentOrNull(content + "\n\nSó você pode ver esta • Ignorar mensagem")
                                .onErrorResume(e -> {
                                    log.warn("Erro ao editar resposta temporária: {}", e.getMessage());
                                    return Mono.empty();
                                })))
                .then();
    }

    /**
     * Versão padrão com 10 segundos
     */
    public Mono<Void> sendTemporaryInitialResponse(ButtonInteractionEvent event, String content) {
        return sendTemporaryInitialResponse(event, content, 10);
    }

    /**
     * Versão que suporta eventos ChatInput
     */
    public Mono<Void> sendTemporaryFollowupMessage(ChatInputInteractionEvent event, String content, int seconds) {
        return event.createFollowup()
                .withEphemeral(true)
                .withContent(content + "\nEsta mensagem desaparecerá em " + seconds + " segundos.")
                .flatMap(message -> {
                    // Log que a mensagem foi enviada
                    log.debug("Mensagem temporária enviada: {}", message.getId().asString());

                    // Schedule dismissal advice after half the timeout
                    return Mono.delay(Duration.ofSeconds(seconds/2))
                            .then(Mono.fromRunnable(() -> {
                                try {
                                    // Update to add dismissal advice
                                    message.edit()
                                            .withContentOrNull(content + "\n\nSó você pode ver esta • Ignorar mensagem")
                                            .subscribe();
                                } catch (Exception e) {
                                    log.warn("Erro ao atualizar mensagem temporária: {}", e.getMessage());
                                }
                            }))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then();
                });
    }

    /**
     * Versão ChatInput com tempo padrão de 10 segundos
     */
    public Mono<Void> sendTemporaryFollowupMessage(ChatInputInteractionEvent event, String content) {
        return sendTemporaryFollowupMessage(event, content, 10);
    }
}