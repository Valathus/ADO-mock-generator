package com.example.adomock.service;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.AdminIdentityProvider;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class WebhookServiceManager {

	private static final Logger log = LoggerFactory.getLogger(WebhookServiceManager.class);

	private final AdoRestClient adoClient;
	private final AdoProperties properties;
	private final AdminIdentityProvider adminIdentityProvider;
	private final FileStateRepository repository;

	@Value("${mock.tfvc.enabled:false}")
	private boolean tfvcEnabled;

	@Value("${mock.release.enabled:false}")
	private boolean releaseEnabled;

	@Value("${mock.testrun.enabled:false}")
	private boolean testRunEnabled;

	public WebhookServiceManager(AdoRestClient adoClient, AdoProperties properties,
			AdminIdentityProvider adminIdentityProvider, FileStateRepository repository) {
		this.adoClient = adoClient;
		this.properties = properties;
		this.adminIdentityProvider = adminIdentityProvider;
		this.repository = repository;
	}

	public void ensureSubscriptions() {
		MockState state = repository.load();
		if (state == null || state.collectionDetails == null) {
			return;
		}

		if (!state.webhookEnabled) {
			return;
		}

		MockState.Admin admin = adminIdentityProvider.getAdmin();
		String pat = admin.pat;

		String apiVersion = properties.getApiVersion();
		String listUri = "/_apis/hooks/subscriptions?api-version=" + apiVersion;

		JsonNode existing = adoClient.get(pat, listUri);

		// ---------------------------
		// Work Item Events
		// ---------------------------
		ensureSubscription(existing, state, pat, "workitem.created");
		ensureSubscription(existing, state, pat, "workitem.updated");

		// ---------------------------
		// Git Events
		// ---------------------------
		ensureSubscription(existing, state, pat, "git.push");
		ensureSubscription(existing, state, pat, "git.pullrequest.created");
		ensureSubscription(existing, state, pat, "git.pullrequest.updated");

		// ---------------------------
		// Build Events (full lifecycle)
		// ---------------------------
		ensureSubscription(existing, state, pat, "build.queued", "tfs", "1.0");
		ensureSubscription(existing, state, pat, "build.complete", "tfs", "1.0");

		// ---------------------------
		// TFVC Events (only if TFVC is enabled)
		// ---------------------------
		if (tfvcEnabled) {
			ensureSubscription(existing, state, pat, "tfvc.checkin", "tfs", "1.0");
		}

		// ---------------------------
		// Release Pipeline Events (classic releases, publisher = rm)
		// ---------------------------
		if (releaseEnabled) {
			ensureSubscription(existing, state, pat, "ms.vss-release.release-created-event", "rm", "3.0-preview.1");
			ensureSubscription(existing, state, pat, "ms.vss-release.deployment-started-event", "rm", "3.0-preview.1");
			ensureSubscription(existing, state, pat, "ms.vss-release.deployment-completed-event", "rm", "3.0-preview.1");
			ensureSubscription(existing, state, pat, "ms.vss-release.deployment-approval-pending-event", "rm", "3.0-preview.1");
		}

		// ---------------------------
		// Test Run Events
		// ---------------------------
		if (testRunEnabled) {
			ensureSubscription(existing, state, pat, "testrun.created", "tfs", "1.0");
			ensureSubscription(existing, state, pat, "testrun.completed", "tfs", "1.0");
		}

		// Enable webhook mode in state only after successful subscription setup
		state.webhookEnabled = true;
		repository.save(state);
	}

	private void ensureSubscription(JsonNode existing, MockState state, String pat, String eventType) {
		ensureSubscription(existing, state, pat, eventType, "tfs", "1.0");
	}

	private void ensureSubscription(JsonNode existing, MockState state, String pat, String eventType,
			String publisherId, String resourceVersion) {

		if (subscriptionExists(existing, state, eventType)) {
			return;
		}

		String apiVersion = properties.getApiVersion();

		Map<String, Object> consumerInputs = new HashMap<>();
		consumerInputs.put("url", state.collectionDetails.webhookURL);

		Map<String, Object> publisherInputs = new HashMap<>();
		publisherInputs.put("projectId", state.collectionDetails.projectId);

		Map<String, Object> payload = new HashMap<>();
		payload.put("publisherId", publisherId);
		payload.put("eventType", eventType);
		payload.put("resourceVersion", resourceVersion);
		payload.put("consumerId", "webHooks");
		payload.put("consumerActionId", "httpRequest");
		payload.put("consumerInputs", consumerInputs);
		payload.put("publisherInputs", publisherInputs);

		String createUri = "/_apis/hooks/subscriptions?api-version=" + apiVersion;

		try {
			adoClient.post(pat, createUri, payload);
			log.info("Created subscription for event {} (publisher={})", eventType, publisherId);
		} catch (Exception e) {
			log.warn("Subscription registration failed for event {} | err={}", eventType, e.getMessage());
		}
	}

	private boolean subscriptionExists(JsonNode existing, MockState state, String eventType) {

		if (existing == null || !existing.has("value")) {
			return false;
		}

		Iterator<JsonNode> iterator = existing.get("value").elements();

		while (iterator.hasNext()) {

			JsonNode node = iterator.next();

			String existingEvent = node.path("eventType").asText();
			String existingUrl = node.path("consumerInputs").path("url").asText();

			if (eventType.equalsIgnoreCase(existingEvent)
					&& state.collectionDetails.webhookURL.equalsIgnoreCase(existingUrl)) {
				return true;
			}
		}

		return false;
	}
}
