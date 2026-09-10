package com.socp.notify.web.api.controller;
import com.socp.notify.web.api.request.ChannelCreateRequest;
import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.persistence.store.ChannelStore;
import com.socp.notify.web.service.NotificationDispatcher;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class NotifyChannelEditingTest {
    @Test void editKeepsIdentityAndTestTargetsOnlySelectedChannel() {
        ChannelStore channels = mock(ChannelStore.class);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        Channel original = new Channel("one", "Original", "LOG", "local", false, "");
        when(channels.get("one")).thenReturn(original);
        when(channels.add(any())).thenAnswer(call -> call.getArgument(0));
        when(dispatcher.test(original)).thenReturn(Map.of("status", "logged"));
        NotifyController controller = new NotifyController(channels, dispatcher);
        Channel updated = controller.update("one", new ChannelCreateRequest("Edited", "LOG", "local", false, "note"));
        assertThat(updated.id()).isEqualTo("one");
        assertThat(updated.name()).isEqualTo("Edited");
        assertThat(controller.test("one").getStatusCode().value()).isEqualTo(200);
        verify(dispatcher).test(original);
        verify(dispatcher, never()).dispatch(any());
        assertThatThrownBy(() -> controller.update("missing", new ChannelCreateRequest("Edited", "LOG", "local", false, "")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
