package org.ligoj.app.plugin.scm;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ScriptContext {

	/**
	 * The id of the script to execute.
	 */
	private String scriptId;

	/**
	 * The arguments to pass to the script as environment variables
	 */
	private Map<String, String> parameters;

}
