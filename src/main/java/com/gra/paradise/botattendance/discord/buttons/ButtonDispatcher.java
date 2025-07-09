package com.gra.paradise.botattendance.discord.buttons;

import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ButtonDispatcher {

    private final ScheduleSystemButtonHandler scheduleSystemButtonHandler;

    public Mono<Void> handleButtonEvent(ButtonInteractionEvent event) {
        String customId = event.getCustomId();
        log.debug("Botão clicado: {}", customId);

        if (customId.equals("create_schedule")) {
            return scheduleSystemButtonHandler.handleCreateScheduleButton(event);
        } else if (customId.startsWith("confirm_schedule:")) {
            return scheduleSystemButtonHandler.handleConfirmSchedule(event);
        } else if (customId.equals("cancel_schedule")) {
            return scheduleSystemButtonHandler.handleCancelSchedule(event);
        } else if (customId.startsWith("board_schedule:")) {
            return scheduleSystemButtonHandler.handleBoardSchedule(event);
        } else if (customId.startsWith("leave_schedule:")) {
            return scheduleSystemButtonHandler.handleLeaveSchedule(event);
        } else if (customId.startsWith("end_schedule:")) {
            return scheduleSystemButtonHandler.handleEndSchedule(event);
        }

        log.warn("Botão não reconhecido: {}", customId);
        return event.reply()
                .withContent("Este botão não está implementado.")
                .withEphemeral(true);
    }

    public Mono<Void> handleSelectMenuEvent(SelectMenuInteractionEvent event) {
        String customId = event.getCustomId();
        log.debug("Menu selecionado: {}", customId);

        if (customId.equals("aircraft_select")) {
            return scheduleSystemButtonHandler.handleAircraftSelection(event);
        } else if (customId.startsWith("mission_select:")) {
            return scheduleSystemButtonHandler.handleMissionSelection(event);
        }

        log.warn("Menu não reconhecido: {}", customId);
        return event.reply()
                .withContent("Este menu não está implementado.")
                .withEphemeral(true);
    }
}