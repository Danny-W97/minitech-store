package org.demo.chatservice.chat;

import org.demo.platformsecurity.Access;
import org.demo.chatservice.web.ChatException;
import org.demo.chatservice.web.ChatMessageResponse;
import org.demo.chatservice.web.CreateChatMessageRequest;
import org.demo.chatservice.web.ReplyChatMessageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChatMessageService {
    private final ChatMessageRepository repository;
    private final ChatEntryRepository entries;
    private final ChatNotificationTaskRepository tasks;

    public ChatMessageService(ChatMessageRepository repository, ChatEntryRepository entries,ChatNotificationTaskRepository tasks) {
        this.repository = repository;
        this.entries=entries;this.tasks=tasks;
    }

    @Transactional
    public ChatMessageResponse create(CreateChatMessageRequest request) {
        ChatMessage message = new ChatMessage(request.customerName(), request.customerEmail(), request.subject(), request.message());
        if (Access.request() != null) message.setAccessTokenHash(Access.capabilityHash(Access.CHAT_TOKEN));
        message = repository.saveAndFlush(message);
        saveEntry(message,"CUSTOMER",true,"initial",request.message());
        return ChatMessageResponse.from(message);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> all() {
        return list(null, "", org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true)
    public org.demo.platformsecurity.ListWindow.ListPage<ChatMessageResponse> list(ChatMessageStatus status, String search,
            org.demo.platformsecurity.ListWindow window) {
        return window.map(repository.page(window.ceiling(), status, org.demo.platformsecurity.ListWindow.search(search), window.fetch()),
                ChatMessage::getId, ChatMessageResponse::from);
    }

    @Transactional(readOnly = true)
    public ChatMessageResponse get(Long id) {
        ChatMessage message = find(id);
        Access.owner(null, message.getAccessTokenHash(), Access.CHAT_TOKEN);
        return ChatMessageResponse.from(message);
    }

    @Transactional
    public ChatMessageResponse reply(Long id, ReplyChatMessageRequest request) {
        Access.require(Access.actor().operator());
        return append(id,request,"SUPPORT");
    }

    @Transactional
    public ChatMessageResponse followUp(Long id,ReplyChatMessageRequest request) {
        return append(id,request,"CUSTOMER");
    }
    private ChatMessageResponse append(Long id,ReplyChatMessageRequest request,String author) {
        ChatMessage message=repository.lockId(id).orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND,"Nie znaleziono rozmowy."));
        if ("CUSTOMER".equals(author)) Access.owner(null,message.getAccessTokenHash(),Access.CHAT_TOKEN);
        String key=request.requestId()==null?java.util.UUID.randomUUID().toString():request.requestId();
        var previous=entries.findByConversationIdAndAuthorAndRequestKey(id,author,key).orElse(null);
        if(previous!=null) {
            if(!previous.getMessage().equals(request.message().trim())) throw new ChatException(HttpStatus.CONFLICT,"Ten klucz wiadomości należy do innej treści.");
        } else saveEntry(message,author,false,key,request.message());
        return ChatMessageResponse.from(message);
    }
    private void saveEntry(ChatMessage message,String author,boolean initial,String key,String text) {
        var entry=entries.saveAndFlush(new ChatEntry(message.getId(),author,initial,key,text));
        tasks.save(new ChatNotificationTask(entry));message.recordEntry(entry);
    }
    @Transactional(readOnly=true)
    public History history(Long id,long before,int size) {
        var message=find(id);Access.owner(null,message.getAccessTokenHash(),Access.CHAT_TOKEN);
        if(before<0 || size<1 || size>100) throw new ChatException(HttpStatus.BAD_REQUEST,"Nieprawidłowa strona historii.");
        var page=entries.window(id,before==0?Long.MAX_VALUE:before,org.springframework.data.domain.PageRequest.of(0,size+1));
        boolean more=page.size()>size;var selected=new java.util.ArrayList<>(page.subList(0,Math.min(size,page.size())));
        Long next=more?selected.getLast().getId():null;
        var statuses=new java.util.HashMap<Long,ChatNotificationTask>();
        tasks.findAllById(selected.stream().map(ChatEntry::getId).toList()).forEach(task -> statuses.put(task.getEntryId(),task));
        java.util.Collections.reverse(selected);
        return new History(selected.stream().map(entry -> org.demo.chatservice.web.ChatEntryResponse.from(entry,statuses.get(entry.getId()))).toList(),next,more);
    }
    public record History(java.util.List<org.demo.chatservice.web.ChatEntryResponse> entries,Long nextCursor,boolean hasMore) {}

    private ChatMessage find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND, "Nie znaleziono wiadomości czatu."));
    }
}
