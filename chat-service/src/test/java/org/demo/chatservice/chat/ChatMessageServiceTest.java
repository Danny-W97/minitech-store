package org.demo.chatservice.chat;

import org.demo.chatservice.web.*;
import org.demo.platformsecurity.Access;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatMessageServiceTest {
    private final ChatMessageRepository repository=mock(ChatMessageRepository.class);
    private final ChatEntryRepository entries=mock(ChatEntryRepository.class);
    private final ChatNotificationTaskRepository tasks=mock(ChatNotificationTaskRepository.class);
    private final ChatMessageService service=new ChatMessageService(repository,entries,tasks);
    private long nextEntry=1;
    private final String capability="C".repeat(43);
    private MockHttpServletRequest request;
    @BeforeEach void setup() {
        request=new MockHttpServletRequest();request.addHeader(Access.CHAT_TOKEN,capability);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            ChatMessage message=call.getArgument(0);org.springframework.test.util.ReflectionTestUtils.setField(message,"id",71L);return message;
        });
        when(entries.saveAndFlush(any())).thenAnswer(call -> {
            ChatEntry entry=call.getArgument(0);org.springframework.test.util.ReflectionTestUtils.setField(entry,"id",nextEntry++);return entry;
        });
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    private ChatMessage create() {
        service.create(new CreateChatMessageRequest("Guest","guest@example.test","Title","Question"));
        var saved=org.mockito.ArgumentCaptor.forClass(ChatMessage.class);verify(repository).saveAndFlush(saved.capture());return saved.getValue();
    }
    @Test void aGuestCanReadOnlyWithTheCapabilitySavedAtCreation() {
        var message=create();when(repository.findById(71L)).thenReturn(Optional.of(message));
        assertThat(service.get(71L).message()).isEqualTo("Question");
        request.removeHeader(Access.CHAT_TOKEN);request.addHeader(Access.CHAT_TOKEN,"D".repeat(43));
        assertThatThrownBy(() -> service.get(71L)).isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        request.removeHeader(Access.CHAT_TOKEN);
        assertThatThrownBy(() -> service.get(71L)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void missingCapabilityCannotSaveOrSendAMessage() {
        request.removeHeader(Access.CHAT_TOKEN);
        assertThatThrownBy(() -> service.create(new CreateChatMessageRequest("Guest","guest@example.test",null,"Question")))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verify(repository,never()).saveAndFlush(any());verifyNoInteractions(entries,tasks);
    }
    @Test void creationQueuesDurableNotificationWithoutHttp() {
        var message=create();assertThat(message.getNotificationStatus()).isEqualTo("PENDING");assertThat(message.getMessage()).isEqualTo("Question");
        verify(tasks).save(any(ChatNotificationTask.class));
    }
    @Test void aReplyFailureIsRecordedWithoutLosingTheReply() {
        var message=create();when(repository.lockId(71L)).thenReturn(Optional.of(message));
        request.setAttribute(Access.ACTOR,new org.demo.platformsecurity.Actor("OPERATOR",null,null,null));
        var response=service.reply(71L,new ReplyChatMessageRequest("Answer"));
        assertThat(response.replyMessage()).isEqualTo("Answer");assertThat(response.replyNotificationStatus()).isEqualTo("PENDING");
        assertThat(response.status()).isEqualTo(ChatMessageStatus.ANSWERED);
    }
    @Test void aMissingThreadDoesNotSendAnything() {
        request.setAttribute(Access.ACTOR,new org.demo.platformsecurity.Actor("OPERATOR",null,null,null));
        when(repository.lockId(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reply(99L,new ReplyChatMessageRequest("Answer"))).isInstanceOf(ChatException.class);
        verifyNoInteractions(entries,tasks);
    }
}
