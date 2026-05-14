package com.example.adomock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties(prefix = "ado")
@Validated
public class AdoProperties {

	@NotBlank
	private String apiVersion = "6.0";

	@Min(1)
	private int connectTimeoutMs = 5000;

	@Min(1)
	private int readTimeoutMs = 15000;

	@Valid
	private final Retry retry = new Retry();

	public String getApiVersion() {
		return apiVersion;
	}

	public void setApiVersion(String apiVersion) {
		this.apiVersion = apiVersion;
	}

	public int getConnectTimeoutMs() {
		return connectTimeoutMs;
	}

	public void setConnectTimeoutMs(int connectTimeoutMs) {
		this.connectTimeoutMs = connectTimeoutMs;
	}

	public int getReadTimeoutMs() {
		return readTimeoutMs;
	}

	public void setReadTimeoutMs(int readTimeoutMs) {
		this.readTimeoutMs = readTimeoutMs;
	}

	public Retry getRetry() {
		return retry;
	}

	public int getRetryMaxAttempts() {
		return retry.getMaxAttempts();
	}

	public int getRetryBackoffMs() {
		return retry.getBackoffMs();
	}

	public static class Retry {

		@Min(1)
		private int maxAttempts = 3;

		@Min(1)
		private int backoffMs = 500;

		public int getMaxAttempts() {
			return maxAttempts;
		}

		public void setMaxAttempts(int maxAttempts) {
			this.maxAttempts = maxAttempts;
		}

		public int getBackoffMs() {
			return backoffMs;
		}

		public void setBackoffMs(int backoffMs) {
			this.backoffMs = backoffMs;
		}
	}
}
