package com.gra.paradise.botattendance.service;

import com.gra.paradise.botattendance.model.*;
import com.gra.paradise.botattendance.repository.*;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.core.spec.MessageEditSpec;
import discord4j.rest.http.client.ClientException;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleLogRepository scheduleLogRepository;
    private final UserService userService;

    // Injetar o client do Discord para poder enviar/atualizar mensagens
    @Autowired
    private GatewayDiscordClient discordClient;

    // Armazenar referências a mensagens de escalas
    private final Map<String, ScheduleMessageInfo> scheduleMessages = new HashMap<>();

    // Classe interna para armazenar informações de mensagens
    private static class ScheduleMessageInfo {
        String channelId;
        String messageId;

        ScheduleMessageInfo(String channelId, String messageId) {
            this.channelId = channelId;
            this.messageId = messageId;
        }
    }

    // Armazenar os canais de logs por tipo de missão
    private Map<MissionType, String> logChannelIds = new HashMap<>();
    // Armazenar mensagens de log por escala
    private final Map<Long, String> scheduleLogMessages = new HashMap<>();

    // Formatador para horários
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private Mono<Void> sendLogMessage(String content, MissionType missionType) {
        String channelId = getLogChannelId(missionType);

        if (channelId == null || discordClient == null) {
            return Mono.empty();
        }

        return discordClient.getChannelById(Snowflake.of(channelId))
                .ofType(MessageChannel.class)
                .flatMap(channel -> channel.createMessage(content))
                .then();
    }

    private String getLogChannelId(MissionType missionType) {
        // Se temos um canal específico para o tipo de missão, use-o
        if (missionType != null && logChannelIds.containsKey(missionType)) {
            return logChannelIds.get(missionType);
        }
        // Caso contrário, tente usar o canal padrão (se existir)
        return logChannelIds.getOrDefault(MissionType.PATROL, null);
    }

    @Transactional
    public Schedule createSchedule(String title, AircraftType aircraftType, MissionType missionType,
                                   String creatorId, String creatorNickname) {
        Schedule schedule = new Schedule();
        schedule.setTitle(title);
        schedule.setAircraftType(aircraftType);
        schedule.setMissionType(missionType);
        schedule.setStartTime(LocalDateTime.now());
        schedule.setCreatedById(creatorId);
        schedule.setCreatedByUsername(creatorNickname);
        schedule.setActive(true);

        Schedule savedSchedule = scheduleRepository.save(schedule);
        log.info("Escala criada: {} (ID: {}, Tipo: {})", title, savedSchedule.getId(), missionType.getDisplayName());

        // Criar log para a criação da escala
        ScheduleLog scheduleLog = new ScheduleLog(
                savedSchedule,
                "CREATED",
                creatorId,
                creatorNickname,
                "Escala criada com aeronave " + aircraftType.getDisplayName() +
                        " para missão de " + missionType.getDisplayName()
        );
        scheduleLogRepository.save(scheduleLog);

        // Enviar log inicial para o canal Discord
        sendScheduleCreationLog(savedSchedule);

        return savedSchedule;
    }

    private void sendScheduleCreationLog(Schedule schedule) {
        EmbedCreateSpec logEmbed = EmbedCreateSpec.builder()
                .title("✅ Nova Escala Criada")
                .description("Uma nova escala de voo foi criada")
                .addField("Escala", schedule.getTitle(), true)
                .addField("Aeronave", schedule.getAircraftType().getDisplayName(), true)
                .addField("Tipo de Missão", schedule.getMissionType().getDisplayName(), true)
                .addField("Escalante", schedule.getCreatedByUsername(), true)
                .addField("Início", formatDateTime(schedule.getStartTime()), true)
                .addField("Tripulação", "Nenhum tripulante embarcado", false)
                .addField("Últimas atividades", formatActivity(schedule.getCreatedByUsername() + ": Escala criada com aeronave " +
                                schedule.getAircraftType().getDisplayName() + " para missão de " + schedule.getMissionType().getDisplayName(),
                        schedule.getStartTime()), false)
                .color(getMissionColor(schedule.getMissionType()))
                .timestamp(Instant.now())
                .build();

        sendLogEmbed(logEmbed, schedule.getMissionType())
                .flatMap(message -> {
                    // Armazenar o ID da mensagem de log para atualizações
                    scheduleLogMessages.put(schedule.getId(), message.getId().asString());
                    return Mono.empty();
                })
                .subscribe();
    }

    @Transactional
    public Schedule addCrewMember(Long scheduleId, String discordId, String username, String nickname) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Escala não encontrada"));

        if (!schedule.isActive()) {
            throw new IllegalStateException("Esta escala já foi encerrada");
        }

        // Verificar se usuário já está na tripulação
        boolean alreadyInCrew = schedule.getCrewMembers().stream()
                .anyMatch(user -> user.getDiscordId().equals(discordId));

        if (alreadyInCrew) {
            throw new IllegalStateException("Você já está embarcado nesta aeronave");
        }

        User user = userService.getOrCreateUser(discordId, username, nickname);
        schedule.addCrewMember(user);

        // Registrar log usando nickname
        ScheduleLog scheduleLog = new ScheduleLog(
                schedule,
                "JOINED",
                discordId,
                nickname,
                "Tripulante embarcou na aeronave"
        );
        scheduleLogRepository.save(scheduleLog);

        Schedule updatedSchedule = scheduleRepository.save(schedule);
        log.info("Tripulante {} embarcou na escala {} (ID: {})", nickname, schedule.getTitle(), scheduleId);

        // Atualizar o log da escala
        updateScheduleLogMessage(updatedSchedule, nickname + " embarcou na aeronave");

        return updatedSchedule;
    }

    @Transactional
    public Schedule removeCrewMember(Long scheduleId, String discordId, String nickname) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Escala não encontrada"));

        if (!schedule.isActive()) {
            throw new IllegalStateException("Esta escala já foi encerrada");
        }

        User user = schedule.getCrewMembers().stream()
                .filter(u -> u.getDiscordId().equals(discordId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Você não está embarcado nesta aeronave"));

        schedule.removeCrewMember(user);

        // Registrar log usando nickname
        ScheduleLog scheduleLog = new ScheduleLog(
                schedule,
                "LEFT",
                discordId,
                nickname,
                "Tripulante desembarcou da aeronave"
        );
        scheduleLogRepository.save(scheduleLog);

        Schedule updatedSchedule = scheduleRepository.save(schedule);
        log.info("Tripulante {} desembarcou da escala {} (ID: {})", nickname, schedule.getTitle(), scheduleId);

        // Atualizar o log da escala
        updateScheduleLogMessage(updatedSchedule, nickname + " desembarcou da aeronave");

        return updatedSchedule;
    }

    @Transactional
    public Schedule closeSchedule(Long scheduleId, String discordId, String nickname) {
        try {
            Schedule schedule = scheduleRepository.findById(scheduleId)
                    .orElseThrow(() -> new IllegalArgumentException("Escala não encontrada"));

            if (!schedule.isActive()) {
                throw new IllegalStateException("Esta escala já foi encerrada");
            }

            LocalDateTime endTime = LocalDateTime.now();
            schedule.setActive(false);
            schedule.setEndTime(endTime);

            // Registrar log usando nickname
            ScheduleLog scheduleLog = new ScheduleLog(
                    schedule,
                    "CLOSED",
                    discordId,
                    nickname,
                    "Escala encerrada"
            );
            scheduleLogRepository.save(scheduleLog);

            Schedule updatedSchedule = scheduleRepository.save(schedule);
            log.info("Escala {} (ID: {}) encerrada por {}", schedule.getTitle(), scheduleId, nickname);

            // Copie os dados necessários para usar fora da transação
            final Long scheduleIdFinal = updatedSchedule.getId();
            final String titleFinal = updatedSchedule.getTitle();
            final AircraftType aircraftTypeFinal = updatedSchedule.getAircraftType();
            final MissionType missionTypeFinal = updatedSchedule.getMissionType();
            final LocalDateTime startTimeFinal = updatedSchedule.getStartTime();
            final LocalDateTime endTimeFinal = updatedSchedule.getEndTime();
            final String creatorFinal = updatedSchedule.getCreatedByUsername();

            // Inicialize os dados de tripulantes dentro da transação
            List<String> crewNicknames = new ArrayList<>();
            if (updatedSchedule.getCrewMembers() != null) {
                for (User user : updatedSchedule.getCrewMembers()) {
                    crewNicknames.add(user.getNickname());
                }
            }

            // Atualizar o log da escala para incluir o encerramento - usando os dados copiados
            createFinalScheduleLogMessage(
                    scheduleIdFinal,
                    titleFinal,
                    aircraftTypeFinal,
                    missionTypeFinal,
                    startTimeFinal,
                    endTimeFinal,
                    creatorFinal,
                    nickname,
                    crewNicknames
            );

            return updatedSchedule;
        } catch (Exception e) {
            log.error("Erro ao encerrar escala {}: {}", scheduleId, e.getMessage(), e);
            throw e;
        }
    }

    // Método para criar a mensagem final de log quando a escala é encerrada
    private void createFinalScheduleLogMessage(
            Long scheduleId,
            String title,
            AircraftType aircraftType,
            MissionType missionType,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String pilotName,
            String closedByName,
            List<String> crewNicknames) {

        // Calcular estatísticas da escala
        String duration = formatDuration(startTime, endTime);

        // Formatação da lista de tripulantes com tempos
        String crewList;
        if (crewNicknames.isEmpty()) {
            crewList = "Nenhum tripulante embarcou nesta escala";
        } else {
            StringBuilder sb = new StringBuilder();
            for (String nickname : crewNicknames) {
                sb.append(nickname)
                        .append(" - ")
                        .append(duration) // Simplificado: todos com o tempo total
                        .append("\n");
            }
            crewList = sb.toString();
        }

        // Obter histórico completo de atividades
        String activityHistory = getActivityHistory(scheduleId);

        EmbedCreateSpec finalLogEmbed = EmbedCreateSpec.builder()
                .title("🏁 Escala Encerrada: " + title)
                .description("Esta escala de voo foi concluída")
                .addField("Aeronave", aircraftType.getDisplayName(), true)
                .addField("Tipo de Missão", missionType.getDisplayName(), true)
                .addField("Escalante", pilotName, true)
                .addField("Início", formatDateTime(startTime), true)
                .addField("Término", formatDateTime(endTime), true)
                .addField("Duração Total", duration, true)
                .addField("Tripulantes e tempos de serviço", crewList, false)
                .addField("Histórico de Atividades", activityHistory, false)
                .color(Color.RED)
                .timestamp(Instant.now())
                .build();

        // Se temos uma mensagem existente, atualizá-la, senão criar nova
        String messageId = scheduleLogMessages.get(scheduleId);
        String channelId = getLogChannelId(missionType);

        if (messageId != null && channelId != null) {
            discordClient.getChannelById(Snowflake.of(channelId))
                    .ofType(MessageChannel.class)
                    .flatMap(channel -> channel.getMessageById(Snowflake.of(messageId))
                            .flatMap(message -> message.edit(MessageEditSpec.builder()
                                    .addEmbed(finalLogEmbed)
                                    .build()))
                            .onErrorResume(e -> {
                                if (e.getMessage() != null && e.getMessage().contains("Unknown Message")) {
                                    log.warn("Mensagem de log {} não encontrada. Criando nova mensagem de log.", messageId);
                                    return channel.createMessage(MessageCreateSpec.builder()
                                                    .addEmbed(finalLogEmbed)
                                                    .build())
                                            .doOnNext(newMessage -> scheduleLogMessages.put(scheduleId, newMessage.getId().asString()));
                                }
                                return Mono.error(e);
                            })
                    )
                    .subscribe();
        } else if (channelId != null) {
            sendLogEmbed(finalLogEmbed, missionType)
                    .doOnNext(message -> scheduleLogMessages.put(scheduleId, message.getId().asString()))
                    .subscribe();
        }
    }

    // Obter histórico completo de atividades
    @Transactional(readOnly = true)
    protected String getActivityHistory(Long scheduleId) {
        List<ScheduleLog> logs = scheduleLogRepository.findByScheduleIdOrderByTimestampDesc(scheduleId);

        if (logs.isEmpty()) {
            return "Nenhuma atividade registrada";
        }

        StringBuilder history = new StringBuilder();
        for (ScheduleLog scheduleLog : logs) {
            history.append(formatDateTime(scheduleLog.getTimestamp()))
                    .append(" - ")
                    .append(scheduleLog.getUsername())
                    .append(": ")
                    .append(scheduleLog.getDetails())
                    .append("\n");
        }

        return history.toString();
    }

    // Atualizar a mensagem de log da escala com novas atividades
    private void updateScheduleLogMessage(Schedule schedule, String activityMessage) {
        // Extrair dados antes de fechar a sessão
        Long scheduleId = schedule.getId();
        String title = schedule.getTitle();
        AircraftType aircraftType = schedule.getAircraftType();
        MissionType missionType = schedule.getMissionType();
        LocalDateTime startTime = schedule.getStartTime();
        String pilotName = schedule.getCreatedByUsername();

        // Lista de tripulantes atuais (dentro da transação)
        List<String> crewNames = new ArrayList<>();
        if (schedule.getCrewMembers() != null) {
            for (User user : schedule.getCrewMembers()) {
                crewNames.add(user.getNickname());
            }
        }

        // Executar o resto fora da transação
        String crewList = crewNames.isEmpty()
                ? "Nenhum tripulante embarcado"
                : String.join("\n", crewNames);

        // Buscar logs em uma nova transação
        List<ScheduleLog> recentLogs = getRecentLogs(scheduleId);

        StringBuilder activityLog = new StringBuilder();
        for (ScheduleLog scheduleLog : recentLogs) {
            activityLog.append(formatDateTime(scheduleLog.getTimestamp()))
                    .append(" - ")
                    .append(scheduleLog.getUsername())
                    .append(": ")
                    .append(scheduleLog.getDetails())
                    .append("\n");
        }

        EmbedCreateSpec updatedLogEmbed = EmbedCreateSpec.builder()
                .title("📋 Escala: " + title)
                .description("Escala de voo ativa")
                .addField("Aeronave", aircraftType.getDisplayName(), true)
                .addField("Tipo de Missão", missionType.getDisplayName(), true)
                .addField("Escalante", pilotName, true)
                .addField("Início", formatDateTime(startTime), true)
                .addField("Duração", formatDuration(startTime, LocalDateTime.now()), true)
                .addField("Status", "Ativa", true)
                .addField("Tripulação", crewList, false)
                .addField("Últimas atividades", activityLog.toString(), false)
                .color(getMissionColor(missionType))
                .timestamp(Instant.now())
                .build();

        // Se temos uma mensagem existente, atualizá-la, senão criar nova
        String messageId = scheduleLogMessages.get(scheduleId);
        String channelId = getLogChannelId(missionType);

        if (messageId != null && channelId != null) {
            discordClient.getChannelById(Snowflake.of(channelId))
                    .ofType(MessageChannel.class)
                    .flatMap(channel -> channel.getMessageById(Snowflake.of(messageId)))
                    .flatMap(message -> message.edit(MessageEditSpec.builder()
                            .addEmbed(updatedLogEmbed)
                            .build()))
                    .subscribe();
        } else if (channelId != null) {
            sendLogEmbed(updatedLogEmbed, missionType)
                    .flatMap(message -> {
                        scheduleLogMessages.put(scheduleId, message.getId().asString());
                        return Mono.empty();
                    })
                    .subscribe();
        }
    }

    @Transactional(readOnly = true)
    public List<ScheduleLog> getRecentLogs(Long scheduleId) {
        List<ScheduleLog> logs = scheduleLogRepository.findByScheduleIdOrderByTimestampDesc(scheduleId);
        if (logs.size() > 5) {
            logs = logs.subList(0, 5);
        }
        return logs;
    }

    // Formatação de data e hora
    private String formatDateTime(LocalDateTime dateTime) {
        return dateTime.format(DATE_TIME_FORMATTER);
    }

    // Formatação para atividades recentes
    private String formatActivity(String message, LocalDateTime time) {
        return formatDateTime(time) + " - " + message;
    }

    // Método auxiliar para formatar a duração
    private String formatDuration(LocalDateTime start, LocalDateTime end) {
        long minutes = java.time.Duration.between(start, end).toMinutes();
        long hours = minutes / 60;
        minutes = minutes % 60;

        return String.format("%02d:%02d", hours, minutes);
    }

    private Color getMissionColor(MissionType missionType) {
        return switch (missionType) {
            case PATROL -> Color.BLUE;
            case ACTION -> Color.RED;
            default -> Color.BLUE;
        };
    }

    @Transactional(readOnly = true)
    public List<Schedule> getActiveSchedules() {
        return scheduleRepository.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public List<Schedule> getActiveSchedulesWithDetails() {
        List<Schedule> schedules = getActiveSchedules();
        for (Schedule schedule : schedules) {
            schedule.initializeCrewMembers();
        }
        return schedules;
    }

    @Transactional(readOnly = true)
    public List<ScheduleLog> getScheduleLogs(Long scheduleId) {
        return scheduleLogRepository.findByScheduleIdOrderByTimestampDesc(scheduleId);
    }

    @Transactional
    public void updateMessageInfo(Long scheduleId, String messageId, String channelId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Escala não encontrada"));

        schedule.setMessageId(messageId);
        schedule.setChannelId(channelId);

        scheduleRepository.save(schedule);
    }

    @Transactional(readOnly = true)
    public Optional<Schedule> findByMessageInfo(String messageId, String channelId) {
        return scheduleRepository.findByMessageIdAndChannelId(messageId, channelId);
    }

    @Transactional
    public Schedule addCrewMemberAndInitialize(Long scheduleId, String userId, String username, String nickname) {
        Schedule schedule = addCrewMember(scheduleId, userId, username, nickname);
        return schedule;  // Já inicializamos dentro do método addCrewMember
    }

    @Transactional
    public Schedule removeCrewMemberAndInitialize(Long scheduleId, String userId, String nickname) {
        Schedule schedule = removeCrewMember(scheduleId, userId, nickname);
        return schedule;  // Já inicializamos dentro do método removeCrewMember
    }

    @Transactional
    public Schedule closeScheduleAndInitialize(Long scheduleId, String userId, String nickname) {
        Schedule schedule = closeSchedule(scheduleId, userId, nickname);
        return schedule;  // Já inicializamos dentro do método closeSchedule
    }

    private String systemMessageChannelId;
    private String systemMessageId;

    public Mono<Void> registerSystemMessage(String channelId, String messageId) {
        this.systemMessageChannelId = channelId;
        this.systemMessageId = messageId;
        return Mono.empty();
    }

    @Transactional(readOnly = true)
    public String generateGraTitle() {
        int activeCount = getActiveSchedules().size() + 1;
        return "G.R.A - " + activeCount;
    }

    // ===== MÉTODOS PARA GERENCIAR MENSAGENS DE ESCALA =====

    public Mono<Void> registerScheduleMessage(String scheduleId, String channelId, String messageId) {
        scheduleMessages.put(scheduleId, new ScheduleMessageInfo(channelId, messageId));

        try {
            Long id = Long.parseLong(scheduleId);
            Schedule schedule = scheduleRepository.findById(id).orElse(null);
            if (schedule != null) {
                schedule.setMessageId(messageId);
                schedule.setChannelId(channelId);
                scheduleRepository.save(schedule);
            }
        } catch (NumberFormatException e) {
            // Ignora se o ID não for um número válido
        }

        return Mono.empty();
    }

    @Transactional
    public Mono<Void> updateScheduleMessage(String scheduleId) {
        if (!scheduleMessages.containsKey(scheduleId)) {
            return Mono.empty();
        }

        ScheduleMessageInfo info = scheduleMessages.get(scheduleId);
        Schedule schedule;

        try {
            Long id = Long.parseLong(scheduleId);
            schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Escala não encontrada"));

            // Copiar dados antes de sair da transação
            final String title = schedule.getTitle();
            final AircraftType aircraftType = schedule.getAircraftType();
            final MissionType missionType = schedule.getMissionType();
            final String pilotName = schedule.getCreatedByUsername();

            // Lista de tripulantes
            List<String> crewNicknames = new ArrayList<>();
            if (schedule.getCrewMembers() != null) {
                for (User user : schedule.getCrewMembers()) {
                    crewNicknames.add(user.getNickname());
                }
            }

            // Continuar fora da transação
            String crewList = crewNicknames.isEmpty()
                    ? "Nenhum tripulante embarcado"
                    : String.join("\n", crewNicknames);

            EmbedCreateSpec embed = EmbedCreateSpec.builder()
                    .title("Escala: " + title)
                    .description("Escala de voo ativa")
                    .addField("Aeronave", aircraftType.getDisplayName(), true)
                    .addField("Tipo de Missão", missionType.getDisplayName(), true)
                    .addField("Escalante", pilotName, true)
                    .addField("Status", "Ativa" + (crewNicknames.isEmpty() ? " - Aguardando tripulantes" : ""), false)
                    .addField("Tripulantes", crewList, false)
                    .color(getMissionColor(missionType))
                    .timestamp(Instant.now())
                    .build();

            Button boardButton = Button.success("board_schedule:" + scheduleId, "Embarcar");
            Button leaveButton = Button.danger("leave_schedule:" + scheduleId, "Desembarcar");
            Button endButton = Button.secondary("end_schedule:" + scheduleId, "Encerrar Escala");

            return discordClient.getChannelById(Snowflake.of(info.channelId))
                    .ofType(MessageChannel.class)
                    .flatMap(channel -> channel.getMessageById(Snowflake.of(info.messageId))
                            .flatMap(message -> message.edit(MessageEditSpec.builder()
                                    .addEmbed(embed)
                                    .addComponent(ActionRow.of(boardButton, leaveButton, endButton))
                                    .build()))
                            // Tratar o erro quando a mensagem não for encontrada
                            .onErrorResume(e -> {
                                if (e.getMessage() != null && e.getMessage().contains("Unknown Message")) {
                                    log.warn("Mensagem {} no canal {} não encontrada ao tentar atualizar escala {}. A mensagem pode ter sido excluída.",
                                            info.messageId, info.channelId, scheduleId);
                                    scheduleMessages.remove(scheduleId); // Remove a referência à mensagem inválida
                                    return Mono.empty();
                                }
                                return Mono.error(e);
                            })
                    )
                    .then();

        } catch (Exception e) {
            log.error("Erro ao atualizar mensagem da escala {}: {}", scheduleId, e.getMessage(), e);
            return Mono.error(e);
        }
    }

    public Mono<Void> removeScheduleMessage(String scheduleId) {
        if (!scheduleMessages.containsKey(scheduleId)) {
            return Mono.empty();
        }

        ScheduleMessageInfo info = scheduleMessages.get(scheduleId);

        EmbedCreateSpec embed = EmbedCreateSpec.builder()
                .title("Escala Encerrada")
                .description("Esta escala de voo foi encerrada.")
                .color(Color.DARK_GRAY)
                .timestamp(Instant.now())
                .build();

        // Melhorar o tratamento de erros
        return discordClient.getChannelById(Snowflake.of(info.channelId))
                .ofType(MessageChannel.class)
                .flatMap(channel ->
                        channel.getMessageById(Snowflake.of(info.messageId))
                                .flatMap(message -> message.edit(MessageEditSpec.builder()
                                        .addEmbed(embed)
                                        .components(List.of())
                                        .build()))
                                .onErrorResume(error -> {
                                    // Se a mensagem não existe, apenas logue o erro e continue
                                    if (error instanceof ClientException &&
                                            ((ClientException) error).getStatus().code() == 404) {
                                        log.warn("Mensagem {} no canal {} não encontrada ao encerrar escala {}. A mensagem pode ter sido excluída.",
                                                info.messageId, info.channelId, scheduleId);
                                        return Mono.empty();
                                    }
                                    return Mono.error(error); // Propaga outros tipos de erro
                                })
                )
                .onErrorResume(error -> {
                    log.error("Erro ao remover mensagem da escala {}: {}", scheduleId, error.getMessage());
                    return Mono.empty();
                })
                .doFinally(signal -> scheduleMessages.remove(scheduleId))
                .then();
    }

    @Transactional
    public boolean addPassenger(String scheduleId, String userId, String userName) {
        try {
            Long id = Long.parseLong(scheduleId);
            addCrewMember(id, userId, userName, userName);
            return true;
        } catch (Exception e) {
            log.error("Erro ao adicionar passageiro: {}", e.getMessage(), e);
            return false;
        }
    }

    @Transactional
    public boolean removePassenger(String scheduleId, String userId) {
        try {
            Long id = Long.parseLong(scheduleId);
            Schedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Escala não encontrada"));

            String nickname = schedule.getCrewMembers().stream()
                    .filter(u -> u.getDiscordId().equals(userId))
                    .findFirst()
                    .map(User::getNickname)
                    .orElse("Desconhecido");

            removeCrewMember(id, userId, nickname);
            return true;
        } catch (Exception e) {
            log.error("Erro ao remover passageiro: {}", e.getMessage(), e);
            return false;
        }
    }

    @Transactional(readOnly = true)
    public boolean canEndSchedule(String scheduleId, String userId) {
        try {
            Long id = Long.parseLong(scheduleId);
            Schedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Escala não encontrada"));

            boolean canEnd = schedule.getCreatedById().equals(userId);
            log.debug("Verificação para encerrar escala {}: usuário {} {} permissão",
                    scheduleId, userId, canEnd ? "tem" : "não tem");
            return canEnd;
        } catch (Exception e) {
            log.error("Erro ao verificar permissão para encerrar: {}", e.getMessage(), e);
            return false;
        }
    }

    @Transactional
    public boolean endSchedule(String scheduleId) {
        try {
            Long id = Long.parseLong(scheduleId);
            Schedule schedule = scheduleRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Escala não encontrada"));

            closeSchedule(id, schedule.getCreatedById(), schedule.getCreatedByUsername());
            return true;
        } catch (Exception e) {
            log.error("Erro ao encerrar escala {}: {}", scheduleId, e.getMessage(), e);
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Mono<Void> updateSystemMessage() {
        if (systemMessageId == null || systemMessageChannelId == null) {
            return Mono.empty();
        }

        // Buscar dados dentro da transação
        List<Schedule> activeSchedules = getActiveSchedules();

        // Criar string de escalas ativas
        StringBuilder sb = new StringBuilder();
        for (Schedule schedule : activeSchedules) {
            sb.append("• ").append(schedule.getTitle()).append(" - ")
                    .append(schedule.getAircraftType().getDisplayName())
                    .append(" (").append(schedule.getMissionType().getDisplayName()).append(")")
                    .append("\n");
        }

        String scheduleList = sb.length() > 0 ? sb.toString() : "Nenhuma escala ativa no momento";

        // Criar embed
        EmbedCreateSpec embed = EmbedCreateSpec.builder()
                .title("Sistema de Escalas de Voo")
                .description("Clique no botão abaixo para criar uma nova escala de voo.")
                .addField("Instruções", "1. Clique em 'Criar Escala'\n2. Selecione a aeronave\n3. Selecione o tipo de missão\n4. Confirme", false)
                .addField("Escalas Ativas", scheduleList, false)
                .timestamp(Instant.now())
                .color(Color.BLUE)
                .build();

        Button createButton = Button.primary("create_schedule", "Criar Escala");

        // Atualizar a mensagem
        return discordClient.getChannelById(Snowflake.of(systemMessageChannelId))
                .ofType(MessageChannel.class)
                .flatMap(channel -> channel.getMessageById(Snowflake.of(systemMessageId)))
                .flatMap(message -> message.edit(MessageEditSpec.builder()
                        .addEmbed(embed)
                        .addComponent(ActionRow.of(createButton))
                        .build()))
                .then();
    }

    private Mono<discord4j.core.object.entity.Message> sendLogEmbed(EmbedCreateSpec embed, MissionType missionType) {
        String channelId = getLogChannelId(missionType);

        if (channelId == null || discordClient == null) {
            return Mono.empty();
        }

        return discordClient.getChannelById(Snowflake.of(channelId))
                .ofType(MessageChannel.class)
                .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                        .addEmbed(embed)
                        .build()));
    }

    public Mono<Void> configureLogChannel(String channelId, MissionType missionType) {
        if (missionType == null) {
            // Canal padrão para todos os tipos
            for (MissionType type : MissionType.values()) {
                this.logChannelIds.put(type, channelId);
            }
            log.info("Canal de logs configurado para todos os tipos de missão: {}", channelId);
        } else {
            // Canal específico para um tipo de missão
            this.logChannelIds.put(missionType, channelId);
            log.info("Canal de logs configurado para missão {}: {}", missionType, channelId);
        }

        // Enviar mensagem de confirmação
        String confirmMessage = "✅ Sistema de logs de escalas configurado com sucesso para " +
                (missionType == null ? "todos os tipos de missão" : "missões do tipo " + missionType.getDisplayName()) + ".";

        return sendLogMessage(confirmMessage, missionType);
    }
}