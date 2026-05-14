package com.example.adomock.identity;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;

import jakarta.annotation.PostConstruct;

@Component
public class UserIdentityProvider {

	private final FileStateRepository repo;
	private final AtomicInteger index = new AtomicInteger(0);

	private List<MockState.User> cachedUsers;

	public UserIdentityProvider(FileStateRepository repo) {
		this.repo = repo;
	}

	@PostConstruct
	public void init() {
		refreshUsers();
	}

	public synchronized void refreshUsers() {
		MockState state = repo.load();

		cachedUsers = state.users.stream().filter(user -> user.enabled).collect(Collectors.toList());

		if (cachedUsers.isEmpty()) {
			throw new RuntimeException("No PAT users configured.");
		}
	}

	public MockState.User next() {

		if (cachedUsers == null || cachedUsers.isEmpty()) {
			refreshUsers();
		}

		int currentIndex = Math.abs(index.getAndIncrement());

		return cachedUsers.get(currentIndex % cachedUsers.size());
	}

	public MockState.User random() {

		if (cachedUsers == null || cachedUsers.isEmpty()) {
			refreshUsers();
		}

		int index = ThreadLocalRandom.current().nextInt(cachedUsers.size());

		return cachedUsers.get(index);
	}

	public List<MockState.User> allUsers() {

		if (cachedUsers == null || cachedUsers.isEmpty()) {
			refreshUsers();
		}

		return cachedUsers;
	}
}
