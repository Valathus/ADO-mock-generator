package com.example.adomock.state;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Component
public class FileStateRepository {

	private final Path statePath;
	private final ObjectMapper mapper;

	public FileStateRepository(@Value("${storage.stateFile}")
	String path) {
		this.statePath = Paths.get(path).toAbsolutePath();

		this.mapper = new ObjectMapper();
		this.mapper.registerModule(new JavaTimeModule());
		this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	}

	public synchronized MockState load() {

		try {

			if (!Files.exists(statePath)) {
				return new MockState();
			}

			return mapper.readValue(statePath.toFile(), MockState.class);

		} catch (IOException e) {
			throw new RuntimeException("Failed to load state file: " + statePath, e);
		}
	}

	public synchronized void save(MockState state) {

		try {

			Path parent = statePath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			Path tempFile = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");

			mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), state);

			try {
				Files.move(tempFile, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ex) {
				Files.move(tempFile, statePath, StandardCopyOption.REPLACE_EXISTING);
			}

		} catch (IOException e) {
			throw new RuntimeException("Failed to save state file: " + statePath, e);
		}
	}
}
