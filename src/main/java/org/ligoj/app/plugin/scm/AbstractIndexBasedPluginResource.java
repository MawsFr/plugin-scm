package org.ligoj.app.plugin.scm;

import java.text.Format;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.resource.NormalizeFormat;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.plugin.AuthCurlProcessor;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.json.InMemoryPagination;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Basic plug-in based on index to populate existing resources.
 * 
 * @see "https://docs.atlassian.com/atlassian-confluence/REST/latest"
 */
@Produces(MediaType.APPLICATION_JSON)
public abstract class AbstractIndexBasedPluginResource extends AbstractToolPluginResource {

	/**
	 * Base URL
	 */
	private final String parameterUrl;

	/**
	 * Repository fragment URL
	 */
	protected final String parameterRepository;

	/**
	 * User authentication.
	 */
	protected final String parameterUser;

	/**
	 * User password.
	 */
	protected final String parameterPassword;

	/**
	 * Has index for parent path?. When undefined, the administration validation cannot be performed. The parameter
	 * value should be a boolean.
	 */
	protected final String parameterIndex;

	@Autowired
	protected InMemoryPagination inMemoryPagination;

	/**
	 * Plug-in key.
	 */
	private final String key;

	/**
	 * Simple plug-in name.
	 */
	protected final String simpleName;

	/**
	 * @param key
	 *            Plug-in key.
	 * @param simpleName
	 *            Simple plug-in name.
	 */
	protected AbstractIndexBasedPluginResource(final String key, final String simpleName) {
		this.key = key;
		this.parameterUrl = key + ":url";
		this.parameterRepository = key + ":repository";
		this.parameterUser = key + ":user";
		this.parameterPassword = key + ":password";
		this.parameterIndex = key + ":index";
		this.simpleName = simpleName;
	}

	@Override
	public String getKey() {
		return key;
	}

	/**
	 * Check the server is available.
	 */
	private void validateAccess(final Map<String, String> parameters) {
		// Validate the access only for HTTP URL and having a root access
		if (getRepositoryUrl(parameters).startsWith("http") && Boolean.valueOf(parameters.getOrDefault(parameterIndex, Boolean.FALSE.toString()))) {
			validateAdminAccess(parameters, newCurlProcessor(parameters));
		}
	}

	/**
	 * Create a new processor using a basic authentication header.
	 * 
	 * @param parameters
	 *            The subscription parameters.
	 * @return The configured {@link CurlProcessor} to use.
	 */
	protected CurlProcessor newCurlProcessor(final Map<String, String> parameters) {
		final String user = parameters.get(parameterUser);
		final String password = StringUtils.trimToEmpty(parameters.get(parameterPassword));

		// Authenticated access
		return new AuthCurlProcessor(user, password);
	}

	/**
	 * Validate the administration connectivity. Expect an authenticated connection.
	 */
	private void validateAdminAccess(final Map<String, String> parameters, final CurlProcessor processor) {
		final CurlRequest request = new CurlRequest(HttpMethod.GET, StringUtils.appendIfMissing(parameters.get(parameterUrl), "/"), null);
		request.setSaveResponse(true);
		// Request all repositories access
		if (!processor.process(request) || !StringUtils.contains(request.getResponse(), "<a href=\"/\">")) {
			throw new ValidationJsonException(parameterUrl, simpleName + "-admin", parameters.get(parameterUser));
		}
	}

	/**
	 * Validate the repository.
	 * 
	 * @param parameters
	 *            the space parameters.
	 * @return Content of root of given repository.
	 */
	protected String validateRepository(final Map<String, String> parameters) {
		final CurlRequest request = new CurlRequest(HttpMethod.GET, getRepositoryUrl(parameters), null);
		request.setSaveResponse(true);
		// Check repository exists
		if (!newCurlProcessor(parameters).process(request)) {
			throw new ValidationJsonException(parameterRepository, simpleName + "-repository", parameters.get(parameterRepository));
		}
		return request.getResponse();
	}

	/**
	 * Return the repository URL.
	 * 
	 * @param parameters
	 *            the subscription parameters.
	 * @return the computed repository URL.
	 */
	protected String getRepositoryUrl(final Map<String, String> parameters) {
		return StringUtils.appendIfMissing(parameters.get(parameterUrl), "/") + parameters.get(parameterRepository);
	}

	@Override
	public void link(final int subscription) {
		// Validate the repository only
		validateRepository(subscriptionResource.getParameters(subscription));
	}

	/**
	 * Find the repositories matching to the given criteria.Look into name only.
	 * 
	 * @param criteria
	 *            the search criteria.
	 * @param node
	 *            the node to be tested with given parameters.
	 * @return project name.
	 */
	@GET
	@Path("{node}/{criteria}")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<NamedBean<String>> findAllByName(@PathParam("node") final String node, @PathParam("criteria") final String criteria) {
		final Map<String, String> parameters = nodeResource.getParametersAsMap(node);
		final CurlRequest request = new CurlRequest(HttpMethod.GET, StringUtils.appendIfMissing(parameters.get(parameterUrl), "/"), null);
		request.setSaveResponse(true);
		newCurlProcessor(parameters).process(request);

		// Prepare the context, an ordered set of projects
		final Format format = new NormalizeFormat();
		final String formatCriteria = format.format(criteria);

		// Limit the result
		return inMemoryPagination.newPage(
				Arrays.stream(StringUtils.splitByWholeSeparator(StringUtils.defaultString(request.getResponse()), "<a href=\"")).skip(1)
						.filter(s -> format.format(s).contains(formatCriteria))
						.map(s -> StringUtils.removeEnd(s.substring(0, Math.max(0, s.indexOf('\"'))), "/"))
						.filter(((Predicate<String>) String::isEmpty).negate()).map(id -> new NamedBean<>(id, id)).collect(Collectors.toList()),
				new PageRequest(0, 10)).getContent();
	}

	@Override
	public String getVersion(final Map<String, String> parameters) {
		// Not implemented
		return null;
	}

	@Override
	public String getLastVersion() {
		// Not implemented
		return null;
	}

	@Override
	public boolean checkStatus(final String node, final Map<String, String> parameters) {
		// Status is UP <=> Administration access is UP (if defined)
		validateAccess(parameters);
		return true;
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final String node, final Map<String, String> parameters) {
		final SubscriptionStatusWithData nodeStatusWithData = new SubscriptionStatusWithData();
		nodeStatusWithData.put("info", toData(validateRepository(parameters)));
		return nodeStatusWithData;
	}

	/**
	 * Return the data to complete the subscription status
	 */
	protected Object toData(final String statusContent) {
		return statusContent;
	}

}