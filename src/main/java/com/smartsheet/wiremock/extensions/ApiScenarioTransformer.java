package com.smartsheet.wiremock.extensions;

import com.fasterxml.jackson.annotation.*;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.*;
import joptsimple.internal.Strings;

public class ApiScenarioTransformer extends ResponseDefinitionTransformer {
	private static final String SCENARIO_HEADER_NAME = "Api-Scenario";
	private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
	private static final String JSON_MIME_TYPE = "application/json";

	private static final ResponseDefinition NO_SCENARIO_RESPONSE =
			new ResponseDefinitionBuilder()
					.withStatus(404)
					.withStatusMessage("Not Found")
					.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
					.withBody(ErrorBody.forReason("No Scenario Provided"))
					.build();

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

		if (responseDefinition.getStatus() != 404) return responseDefinition;

		HttpHeader scenarioHeader = request.header(SCENARIO_HEADER_NAME);

		if(!scenarioHeader.isPresent()) return NO_SCENARIO_RESPONSE;

		if(!scenarioHeader.isSingleValued()) return buildMalformedScenarioResponse(scenarioHeader);

		// At this point: they sent a scenario and we need to validate it:
		//   1. If it's not one we defined, return 404 to indicate they sent an unknown scenario
		//   2. If it is one we defined, return error w/ diff

		return new ResponseDefinitionBuilder()
				.withStatus(422)
				.withStatusMessage("Unprocessable Entity")
				.withBody("No stub found")
				.build();
	}

	private static ResponseDefinition buildMalformedScenarioResponse(HttpHeader scenarioHeader){
		String scenarios = Strings.join(scenarioHeader.values(), ",");
		String body = ErrorBody.forReason("Invalid Scenario: " + scenarios);

		return new ResponseDefinitionBuilder()
				.withStatus(400)
				.withStatusMessage("Bad Request")
				.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
				.withBody(body)
				.build();
	}

	public static final class ErrorBody {
		private final String reason;

		@JsonCreator
		ErrorBody(@JsonProperty ("reason") String reason) {
			this.reason = reason;
		}

		public String getReason() {
			return reason;
		}

		@JsonIgnore
		String toJson() {
			return Json.write(this);
		}

		static String forReason(String reason) {
			return new ErrorBody(reason).toJson();
		}
	}
}
