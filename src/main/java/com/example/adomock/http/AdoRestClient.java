package com.example.adomock.http;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

@Component
public class AdoRestClient {

	private static final Logger log = LoggerFactory.getLogger(AdoRestClient.class);

	private final AdoProperties properties;

	private final HttpClient httpClient;

	private final FileStateRepository repository;

	public AdoRestClient(AdoProperties properties, FileStateRepository repository) {
		this.properties = properties;
		this.httpClient = HttpClient.create()
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs())
				.responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
		this.repository = repository;
	}

	private WebClient createClient(String pat) {
		MockState state = repository.load();
		if (state == null || state.collectionDetails == null || state.collectionDetails.url == null
				|| state.collectionDetails.url.isBlank()) {
			throw new IllegalStateException("collectionDetails.url is not configured in mock state");
		}

		String encoded = Base64.getEncoder().encodeToString((":" + pat).getBytes(StandardCharsets.UTF_8));

		ExchangeStrategies strategies = ExchangeStrategies.builder()
				.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) // 16MB
				).build();

		return WebClient.builder().exchangeStrategies(strategies)
				.clientConnector(new ReactorClientHttpConnector(httpClient)).baseUrl(state.collectionDetails.url)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).build();
	}

	private String patOwnerLabel(String pat) {
		MockState state = repository.load();
		if (state == null) {
			return "unknown-user";
		}

		if (state.admin != null && pat != null && pat.equals(state.admin.pat)) {
			return state.admin.username != null && !state.admin.username.isBlank() ? state.admin.username : "admin";
		}

		if (state.users != null) {
			for (MockState.User user : state.users) {
				if (user != null && pat != null && pat.equals(user.pat)) {
					return user.username != null && !user.username.isBlank() ? user.username : "unknown-user";
				}
			}
		}

		return "unknown-user";
	}

	public JsonNode get(String pat, String uri) {
		String patOwner = patOwnerLabel(pat);
		log.debug("ADO GET {} user={}", uri, patOwner);
		return createClient(pat).get().uri(uri).retrieve()
				.onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
						.map(body -> new RuntimeException(
								"ADO GET Error: " + response.statusCode() + " user=" + patOwner + " -> " + body)))
				.bodyToMono(JsonNode.class).retryWhen(Retry.fixedDelay(properties.getRetryMaxAttempts(),
						Duration.ofMillis(properties.getRetryBackoffMs())))
				.block();
	}

	public JsonNode post(String pat, String uri, Object body) {
		String patOwner = patOwnerLabel(pat);
		log.debug("ADO POST {} user={}", uri, patOwner);
		return createClient(pat).post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve()
				.onStatus(status -> status.isError(),
						response -> response.bodyToMono(String.class)
								.map(errorBody -> new RuntimeException(
										"ADO POST Error: " + response.statusCode() + " user=" + patOwner + " -> " + errorBody)))
				.bodyToMono(JsonNode.class).retryWhen(Retry.fixedDelay(properties.getRetryMaxAttempts(),
						Duration.ofMillis(properties.getRetryBackoffMs())))
				.block();
	}

	public JsonNode patch(String pat, String uri, Object body) {
		String patOwner = patOwnerLabel(pat);
		log.debug("ADO PATCH {} user={}", uri, patOwner);
		return createClient(pat).patch().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve()
				.onStatus(status -> status.isError(),
						response -> response.bodyToMono(String.class)
								.map(errorBody -> new RuntimeException(
										"ADO PATCH Error: " + response.statusCode() + " user=" + patOwner + " -> " + errorBody)))
				.bodyToMono(JsonNode.class).retryWhen(Retry.fixedDelay(properties.getRetryMaxAttempts(),
						Duration.ofMillis(properties.getRetryBackoffMs())))
				.block();
	}

	public JsonNode put(String pat, String uri, Object body) {
		String patOwner = patOwnerLabel(pat);
		log.debug("ADO PUT {} user={}", uri, patOwner);
		return createClient(pat).put().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
				.exchangeToMono(response -> {

					if (response.statusCode().isError()) {
						return response.bodyToMono(String.class).flatMap(errorBody -> reactor.core.publisher.Mono.error(
								new RuntimeException(
										"ADO PUT Error: " + response.statusCode() + " user=" + patOwner + " -> " + errorBody)));
					}

					if (response.statusCode().value() == 204) {
						return reactor.core.publisher.Mono
								.just(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
					}

					return response.bodyToMono(JsonNode.class);
				}).retryWhen(Retry.fixedDelay(properties.getRetryMaxAttempts(),
						Duration.ofMillis(properties.getRetryBackoffMs())))
				.block();
	}

	public JsonNode postJsonPatch(String pat, String uri, Object body) {
		String patOwner = patOwnerLabel(pat);
		log.debug("ADO POST JSON PATCH {} user={}", uri, patOwner);
		return createClient(pat).post().uri(uri).contentType(MediaType.valueOf("application/json-patch+json"))
				.bodyValue(body).retrieve()
				.onStatus(status -> status.isError(),
						response -> response.bodyToMono(String.class)
								.map(errorBody -> new RuntimeException(
										"ADO JSON PATCH POST Error: " + response.statusCode() + " user=" + patOwner + " -> " + errorBody)))
				.bodyToMono(JsonNode.class).retryWhen(Retry.fixedDelay(properties.getRetryMaxAttempts(),
						Duration.ofMillis(properties.getRetryBackoffMs())))
				.block();
	}

	public JsonNode patchJsonPatch(String pat, String uri, Object body) {
		String patOwner = patOwnerLabel(pat);
		log.debug("ADO PATCH JSON PATCH {} user={}", uri, patOwner);
		return createClient(pat).patch().uri(uri).contentType(MediaType.valueOf("application/json-patch+json"))
				.bodyValue(body).retrieve()
				.onStatus(status -> status.isError(),
						response -> response.bodyToMono(String.class)
								.map(errorBody -> new RuntimeException(
										"ADO JSON PATCH Error: " + response.statusCode() + " user=" + patOwner + " -> " + errorBody)))
				.bodyToMono(JsonNode.class).retryWhen(Retry.fixedDelay(properties.getRetryMaxAttempts(),
						Duration.ofMillis(properties.getRetryBackoffMs())))
				.block();
	}

}
