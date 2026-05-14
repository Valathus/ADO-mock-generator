package com.example.adomock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class IdentityResolverService {

	private static final Logger log = LoggerFactory.getLogger(IdentityResolverService.class);

	private final FileStateRepository repository;
	private final AdoRestClient adoClient;
	private final AdoProperties properties;

	public IdentityResolverService(FileStateRepository repository, AdoRestClient adoClient, AdoProperties properties) {
		this.repository = repository;
		this.adoClient = adoClient;
		this.properties = properties;
	}

	public String resolveIdentityDescriptor(MockState.User user) {

		if (user == null || user.username == null || user.username.isBlank()) {
			return null;
		}

		MockState state = repository.load();

		if (user.id != null && !user.id.isBlank()) {
			return user.id;
		}

		String apiVersion = properties.getApiVersion();

		String uri = "/_apis/Identities?searchFilter=General&filterValue=" + user.username + "&api-version="
				+ apiVersion;

		JsonNode response;
		try {
			response = adoClient.get(state.admin.pat, uri);
		} catch (Exception e) {
			log.warn("Failed to resolve identity for user {}", user.username, e);
			return null;
		}

		if (response == null || !response.has("value") || response.path("value").size() == 0) {
			log.warn("Identity not found for user {}", user.username);
			return null;
		}

		JsonNode identity = response.path("value").get(0);

		String id = identity.path("id").asText(null);
		if (id == null || id.isBlank()) {
			log.warn("Identity ID missing for user {}", user.username);
			return null;
		}

		for (MockState.User stateUser : state.users) {
			if (stateUser != null && user.username.equals(stateUser.username)) {
				stateUser.id = id;
				break;
			}
		}
		user.id = id;
		repository.save(state);
		return id;
	}
	
}
