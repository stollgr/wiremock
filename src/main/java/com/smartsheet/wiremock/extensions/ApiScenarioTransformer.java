package com.smartsheet.wiremock.extensions;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.TextFile;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.*;
import static java.lang.System.out;

import java.io.IOException;


public class ApiScenarioTransformer extends ResponseDefinitionTransformer {
	private static final String SCENARIO_HEADER_NAME = "Api-Scenario";
	private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
	private static final String JSON_MIME_TYPE = "application/json";
	private static final String SCENARIO_SCENARIO_FIELD = "scenario";
	private static final String SCENARIOS_DIR = "__scenarios";
	private static final String SCENARIOS_FILE = "scenarios.json";
	private static final String LOGGING_ENABLED_FILE = ".logging_enabled";
	private static final Integer SMARTSHEET_ERROR_CODE = 9999;
	private static final String SMARTSHEET_REF_ID = "123abc";

	private static final ResponseDefinition INVALID_SCENARIO_RESPONSE =
			new ResponseDefinitionBuilder()
					.withStatus(404)
					.withStatusMessage("Not Found")
					.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
					.withBody(ErrorBody.forMessage("No scenario provided", SMARTSHEET_ERROR_CODE, SMARTSHEET_REF_ID))
					.build();

	private boolean isLoggingEnabled = false;

	@Override
	public String getName() {
		return "Sample";
	}

	@Override
	public ResponseDefinition transform(
			Request request,
			ResponseDefinition responseDefinition,
			FileSource files,
			Parameters parameters) {

		SetLoggingEnabled(files);

		if (!scenarioHeaderIsValid(request)) {
			logInfo("Received invalid request header");
			return INVALID_SCENARIO_RESPONSE;
		}

		JsonNode scenario = getScenario(request, files);
		if (scenario == null) return buildUnknownScenarioResponse(request);

		String diff = RequestDiff.getDiff(request, scenario);

		if (isMatched(responseDefinition) && hasNoDiff(diff)) {
			logInfo("- Successfully matched");
			return responseDefinition;
		}

		logInfo("- Match failed");

		return buildDiffResponse(diff);
	}

	private void SetLoggingEnabled(FileSource files) {
		isLoggingEnabled = files.child(SCENARIOS_DIR).child(LOGGING_ENABLED_FILE).exists();
	}

	private void logInfo(String message) {
		if (isLoggingEnabled) {
			out.println(message);
		}
	}

	private boolean isMatched(ResponseDefinition responseDefinition) {
		return responseDefinition.getStatus() != 404;
	}

	private boolean hasNoDiff(String diff) {
		return diff.isEmpty();
	}

	private ResponseDefinition buildDiffResponse(String diff) {
		return new ResponseDefinitionBuilder()
				.withStatus(400)
				.withStatusMessage("Bad Request")
				.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
				.withBody(ErrorBody.forMessage(diff, SMARTSHEET_ERROR_CODE, SMARTSHEET_REF_ID))
				.build();
	}

	private String getScenarioName(Request request) {
		HttpHeader scenarioHeader = request.header(SCENARIO_HEADER_NAME);

		return scenarioHeader.firstValue();
	}

	private JsonNode getScenario(Request request, FileSource files) {
		ArrayNode scenarios = getScenarios(files);

		String scenarioName = getScenarioName(request);

		for (JsonNode scenario : scenarios) {
			if (scenario.get(SCENARIO_SCENARIO_FIELD).textValue().equals(scenarioName)) {
				logInfo(String.format("Received request for '%s'", scenarioName));
				return scenario;
			}
		}

		logInfo(String.format("Could not find scenario '%s'", scenarioName));

		return null;
	}

	private ArrayNode getScenarios(FileSource files) {
		TextFile scenarioFile = files.child(SCENARIOS_DIR).getTextFileNamed(SCENARIOS_FILE);

		ObjectMapper mapper = new ObjectMapper();
		JsonNode scenarios;
		try {
			scenarios = mapper.readTree(scenarioFile.readContentsAsString());
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		return (ArrayNode)scenarios;
	}

	private static boolean scenarioHeaderIsValid(Request request) {
		HttpHeader scenarioHeader = request.header(SCENARIO_HEADER_NAME);

		return scenarioHeader.isPresent() && scenarioHeader.isSingleValued();

	}

	private ResponseDefinition buildUnknownScenarioResponse(Request request) {
		String scenarioName = getScenarioName (request);
		return new ResponseDefinitionBuilder()
				.withStatus(404)
				.withStatusMessage("Not Found")
				.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
				.withBody(ErrorBody.forMessage("No scenario exists with provided name: " + scenarioName, SMARTSHEET_ERROR_CODE, SMARTSHEET_REF_ID))
				.build();
	}

	public static final class ErrorBody {
		private final String message;
		private final Integer errorCode;
		private final String refId;

		@JsonCreator
		ErrorBody(@JsonProperty ("message") String message, @JsonProperty ("errorCode") Integer errorCode, @JsonProperty ("refId") String refId) {
			this.message = message;
			this.errorCode = errorCode;
			this.refId = refId;
		}

		public String getMessage() {
			return message;
		}
		public Integer getErrorCode () { return errorCode; }
		public String getRefId () { return refId; }

		@JsonIgnore
		String toJson() {
			return Json.write(this);
		}

		static String forMessage(String message, Integer errorCode, String refId) {
			return new ErrorBody(message, errorCode, refId).toJson();
		}
	}


}
