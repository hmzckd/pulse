package com.hmzckd.pulse.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

	private final ChatWebSocketHandler handler;
	private final TokenHandshakeInterceptor tokenInterceptor;

	WebSocketConfig(ChatWebSocketHandler handler, TokenHandshakeInterceptor tokenInterceptor) {
		this.handler = handler;
		this.tokenInterceptor = tokenInterceptor;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/ws").addInterceptors(tokenInterceptor);
	}

	@Bean
	ServletServerContainerFactoryBean webSocketContainer() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxTextMessageBufferSize(16 * 1024);
		return container;
	}
}
