package com.example.adomock.service;

import com.example.adomock.state.MockState;

final class IterationNamingSupport {

	private IterationNamingSupport() {
	}

	static String buildPiName(MockState state, int piNumber) {
		return projectLabel(state) + "-PI" + piNumber;
	}

	static String buildSprintName(MockState state, int sprintNumber) {
		return projectLabel(state) + "-Sprint" + sprintNumber;
	}

	static String resolvePiName(MockState state, int sprintNumber) {
		if (state != null && state.programIterations != null) {
			for (MockState.ProgramIteration pi : state.programIterations) {
				if (pi == null || pi.sprints == null) {
					continue;
				}
				for (MockState.Sprint sprint : pi.sprints) {
					if (sprint != null && sprint.sprintNumber == sprintNumber) {
						return isBlank(pi.name) ? buildPiName(state, pi.piNumber) : pi.name;
					}
				}
			}
		}

		int sprintsPerPi = 4;
		if (state != null && state.dataLoadConfig != null && state.dataLoadConfig.sprintsPerPI > 0) {
			sprintsPerPi = state.dataLoadConfig.sprintsPerPI;
		}
		int piNumber = ((sprintNumber - 1) / sprintsPerPi) + 1;
		return buildPiName(state, piNumber);
	}

	static String resolveSprintName(MockState state, int sprintNumber) {
		if (state != null && state.programIterations != null) {
			for (MockState.ProgramIteration pi : state.programIterations) {
				if (pi == null || pi.sprints == null) {
					continue;
				}
				for (MockState.Sprint sprint : pi.sprints) {
					if (sprint != null && sprint.sprintNumber == sprintNumber) {
						return isBlank(sprint.name) ? buildSprintName(state, sprintNumber) : sprint.name;
					}
				}
			}
		}

		return buildSprintName(state, sprintNumber);
	}

	private static String projectLabel(MockState state) {
		if (state != null && state.collectionDetails != null && !isBlank(state.collectionDetails.projectName)) {
			return abbreviateProjectName(state.collectionDetails.projectName);
		}
		return "Project";
	}

	private static String abbreviateProjectName(String projectName) {
		String[] words = projectName.trim().split("\\s+");

		if (words.length >= 3) {
			return (firstChar(words[0]) + firstChar(words[1]) + firstChar(words[2])).toUpperCase();
		}

		if (words.length == 2) {
			return (firstTwo(words[0]) + firstChar(words[1])).toUpperCase();
		}

		String compact = projectName.replaceAll("\\s+", "");
		if (compact.length() >= 3) {
			return compact.substring(0, 3).toUpperCase();
		}
		return compact.toUpperCase();
	}

	private static String firstChar(String value) {
		return isBlank(value) ? "" : value.substring(0, 1);
	}

	private static String firstTwo(String value) {
		if (isBlank(value)) {
			return "";
		}
		return value.length() >= 2 ? value.substring(0, 2) : value.substring(0, 1);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
