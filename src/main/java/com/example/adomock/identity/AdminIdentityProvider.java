package com.example.adomock.identity;

import org.springframework.stereotype.Component;

import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;

@Component
public class AdminIdentityProvider {

    private final FileStateRepository repo;

    public AdminIdentityProvider(FileStateRepository repo) {
        this.repo = repo;
    }

    public MockState.Admin getAdmin() {

        MockState state = repo.load();

        if (state.admin == null || state.admin.pat == null) {
            throw new RuntimeException("Admin PAT not configured.");
        }

        return state.admin;
    }
}

