package com.hmzckd.pulse.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.hmzckd.pulse.web.ProblemAuthenticationEntryPoint;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class SecurityConfig {

	@Bean
	SecretKey jwtKey(JwtProperties properties) {
		return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return NimbusJwtEncoder.withSecretKey(jwtKey).build();
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey) {
		return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint)
			throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/api/sessions").permitAll()
						.requestMatchers("/actuator/health", "/error").permitAll()
						.requestMatchers("/ws").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth -> oauth
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(entryPoint))
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));
		return http.build();
	}
}
