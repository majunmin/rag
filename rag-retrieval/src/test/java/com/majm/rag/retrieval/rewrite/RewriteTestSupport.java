package com.majm.rag.retrieval.rewrite;

import org.springframework.ai.chat.client.ChatClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared scaffolding for rewriter tests. Mocking Spring AI's fluent
 * {@code chatClient.prompt().system(...).user(...).call().content()} chain
 * is verbose; one helper here keeps the rewriter tests focused on behavior.
 */
final class RewriteTestSupport {

    private RewriteTestSupport() {}

    /** Returns a mocked ChatClient whose call().content() yields {@code response}. */
    static ChatClient mockChatClient(String response) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(call);
        when(call.content()).thenReturn(response);
        return chatClient;
    }

    /** Returns a mocked ChatClient whose call().content() throws {@code error}. */
    static ChatClient mockChatClientThrowing(Throwable error) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(call);
        when(call.content()).thenThrow(error instanceof RuntimeException re ? re : new RuntimeException(error));
        return chatClient;
    }
}
