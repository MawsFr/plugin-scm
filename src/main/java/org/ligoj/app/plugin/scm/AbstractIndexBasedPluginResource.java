package org.ligoj.app.plugin.scm;

import java.text.Format;
import java.util.Arrays;
import java.util.HashMap;
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
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.iam.IGroupRepository;
import org.ligoj.app.iam.IamProvider;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.resource.NormalizeFormat;
import org.ligoj.app.resource.node.ParameterResource;
import org.ligoj.app.resource.node.ParameterValueResource;
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

	public static final String HEADER_TOKEN = "token";

	/**
	 * Base URL
	 */
	protected final String parameterUrl;

	/**
	 * The proxy agent url
	 */
	protected final String parameterUrlProxyAgent;

	/**
	 * Repository fragment URL
	 */
	protected final String parameterRepository;

	/**
	 * Client name
	 */
	protected final String parameterOu;

	/**
	 * Project name
	 */
	protected final String parameterProject;

	/**
	 * Project name
	 */
	protected final String parameterLdapGroups;

	/**
	 * User authentication.
	 */
	protected final String parameterUser;

	/**
	 * The secret key to be able to make ajax calls
	 */
	protected String parameterSecretKey;
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

	@Autowired
	protected ParameterValueResource pvResource;

	@Autowired
	protected IamProvider[] iamProvider;

	/**
	 * Plug-in key.
	 */
	private final String key;

	/**
	 * Simple plug-in name, used for validation management.
	 */
	protected final String simpleName;

	/**
	 * The name of the create script.
	 */
	protected String createScript;

	/**
	 * The name of the exists script.
	 */
	protected String existsScript;

	/**
	 * @param key
	 *            Plug-in key.
	 * @param simpleName
	 *            Simple plug-in name.
	 */
	protected AbstractIndexBasedPluginResource(final String key, final String simpleName) {
		this.key = key;
		this.parameterUrl = ScmResource.SERVICE_KEY + ":url";
		this.parameterUrlProxyAgent = ScmResource.SERVICE_KEY + ":url-proxy-agent";
		this.parameterRepository = ScmResource.SERVICE_KEY + ":repository";
		this.parameterOu = ScmResource.SERVICE_KEY + ":ou";
		this.parameterProject = ScmResource.SERVICE_KEY + ":project";
		this.parameterLdapGroups = ScmResource.SERVICE_KEY + ":ldapgroups";
		this.parameterUser = ScmResource.SERVICE_KEY + ":user";
		this.parameterPassword = ScmResource.SERVICE_KEY + ":password";
		this.parameterIndex = ScmResource.SERVICE_KEY + ":index";
		this.parameterSecretKey = ScmResource.SERVICE_KEY + ":secret-key";
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
		if (getRepositoryUrl(parameters).startsWith("http")
				&& Boolean.valueOf(parameters.getOrDefault(parameterIndex, Boolean.FALSE.toString()))) {
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
		final CurlRequest request = new CurlRequest(HttpMethod.GET,
				StringUtils.appendIfMissing(parameters.get(parameterUrl), "/"), null);
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
			throw new ValidationJsonException(parameterRepository, simpleName + "-repository",
					parameters.get(parameterRepository));
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
	public List<NamedBean<String>> findAllByName(@PathParam("node") final String node,
			@PathParam("criteria") final String criteria) {
		final Map<String, String> parameters = pvResource.getNodeParameters(node);
		final CurlRequest request = new CurlRequest(HttpMethod.GET,
				StringUtils.appendIfMissing(parameters.get(parameterUrl), "/"), null);
		request.setSaveResponse(true);
		newCurlProcessor(parameters).process(request);

		// Prepare the context, an ordered set of projects
		final Format format = new NormalizeFormat();
		final String formatCriteria = format.format(criteria);

		// Limit the result
		return inMemoryPagination.newPage(Arrays
				.stream(StringUtils.splitByWholeSeparator(StringUtils.defaultString(request.getResponse()),
						"<a href=\""))
				.skip(1).filter(s -> format.format(s).contains(formatCriteria))
				.map(s -> StringUtils.removeEnd(s.substring(0, Math.max(0, s.indexOf('\"'))), "/"))
				.filter(((Predicate<String>) String::isEmpty).negate()).map(id -> new NamedBean<>(id, id))
				.collect(Collectors.toList()), PageRequest.of(0, 10)).getContent();
	}

	@GET
	@Path("{node}/{fullName}/exists")
	@Consumes(MediaType.APPLICATION_JSON)
	public boolean exists(@PathParam("node") final String node, @PathParam("fullName") final String fullName) {
		final Map<String, String> parameters = pvResource.getNodeParameters(node);
		final String url = parameters.get(parameterUrlProxyAgent);

		final Map<String, String> params = new HashMap<>();
		params.put("REPOSITORY", fullName);
		final ScriptContext context = new ScriptContext();
		context.setScriptId(existsScript);
		context.setArgs(params);
		final CurlRequest request = new CurlRequest(HttpMethod.POST, url, ParameterResource.toJSon(context),
				HttpHeaders.CONTENT_TYPE + ":" + MediaType.APPLICATION_JSON,
				HEADER_TOKEN + ":" + parameters.remove(parameterSecretKey));
		request.setSaveResponse(true);

		// check if creation success
		if (!newCurlProcessor(parameters).process(request)) {
			request.setResponse("-1");
		}

		return handleExistenceError(parameters, request);

	}

	@Override
	public boolean checkStatus(final Map<String, String> parameters) {
		// Status is UP <=> Administration access is UP (if defined)
		validateAccess(parameters);
		return true;
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final Map<String, String> parameters) {
		final SubscriptionStatusWithData nodeStatusWithData = new SubscriptionStatusWithData();
		nodeStatusWithData.put("info", toData(validateRepository(parameters)));
		return nodeStatusWithData;
	}

	/**
	 * Return the data to complete the subscription status.
	 * 
	 * @param statusContent
	 *            The status data content as returned by the index..
	 * @return The status data to put in "info".
	 */
	protected Object toData(final String statusContent) {
		// By default, return the content as is
		return statusContent;
	}

	/**
	 * Group repository provider.
	 *
	 * @return Group repository provider.
	 */
	protected IGroupRepository getGroup() {
		return (IGroupRepository) iamProvider[0].getConfiguration().getGroupRepository();
	}

	@Override
	public void create(int subscription) throws Exception {
		// Create the git repository
		final Map<String, String> parameters = pvResource.getSubscriptionParameters(subscription);

		verifyParameterValid(parameters, subscription);

		String tmp;

		// TODO : Create constants
		tmp = parameters.remove(parameterOu);
		parameters.put("OU", tmp);

		tmp = parameters.remove(parameterProject);
		parameters.put("PROJECT", tmp);

		tmp = parameters.remove(parameterLdapGroups);
		String newGroupsArray = "";
		final IGroupRepository repository = getGroup();
		String[] groups = tmp.split(",");
		for (final String group : groups) {
			newGroupsArray = newGroupsArray.concat(repository.findById(group).getDn() + " ");
		}
		newGroupsArray = newGroupsArray.trim();

		parameters.put("LDAP_GROUPS", newGroupsArray);

		tmp = parameters.remove(parameterUrl);
		parameters.put("URL", StringUtils.appendIfMissing(tmp, "/"));

		tmp = parameters.remove(parameterUrlProxyAgent);
		parameters.put("URL_PROXY_AGENT", StringUtils.appendIfMissing(tmp, "/"));

		tmp = parameters.remove(parameterUser);
		parameters.put("USER", tmp);

		tmp = parameters.remove(parameterPassword);
		parameters.put("PASSWORD", tmp);

		ScriptContext context = new ScriptContext();
		context.setScriptId(createScript);
		context.setArgs(parameters);
		final CurlRequest request = new CurlRequest(HttpMethod.POST, parameters.get("URL_PROXY_AGENT"),
				ParameterResource.toJSon(context), HttpHeaders.CONTENT_TYPE + ":" + MediaType.APPLICATION_JSON,
				HEADER_TOKEN + ":" + parameters.remove(parameterSecretKey));
		request.setSaveResponse(true);

		// check if creation success
		if (!newCurlProcessor(parameters).process(request)) {
			request.setResponse("-1");
		}
		handleCreationError(parameters, request);
	}

	/**
	 * Verifies that the parameters are valid after a subscription creation
	 * 
	 * @param parameters
	 *            Parameters sent when creating the subscription
	 * @param subscriptionId
	 *            The id of the subscription
	 */
	protected void verifyParameterValid(final Map<String, String> parameters, final int subscriptionId) {
		final String OU = parameters.get(parameterOu);
		final String PROJECT = parameters.get(parameterProject);
		final String URL = parameters.get(parameterUrl);
		final String URL_PROXY_AGENT = parameters.get(parameterUrlProxyAgent);
		final String LDAP_GROUPS = parameters.get(parameterLdapGroups);
		final String USER = parameters.get(parameterUser);
		final String PASSWORD = parameters.get(parameterPassword);
		final String AUTH_SECRET_KEY = parameters.get(parameterSecretKey);

		if (StringUtils.isBlank(OU) || StringUtils.isBlank(URL) || StringUtils.isBlank(URL_PROXY_AGENT)
				|| StringUtils.isBlank(LDAP_GROUPS) || StringUtils.isBlank(USER) || StringUtils.isBlank(PASSWORD)
				|| StringUtils.isBlank(AUTH_SECRET_KEY)) {
			throw new ValidationJsonException("Complete all parameters");
		}

		final Subscription subscription = subscriptionRepository.findOne(subscriptionId);
		final Project project = subscription.getProject();

		if (!project.getName().equals(PROJECT)) {
			throw new ValidationJsonException("Project invalid");
		}

		// TODO : Maybe verify that the ldap groups can be accessed by the user ?

	}

	// TODO : implement
	// protected void formatParameterKeysToBashVariables(Map<String, String> parameters) {
	// for (Map.Entry<String, String> entry : parameters.entrySet()) {
	// String tmp = parameters.remove(entry.getKey());
	// String key = entry.getKey();
	// String newKey = key.substring(key.lastIndexOf(":")).replaceAll("-", "_").toUpperCase();
	// parameters.put(newKey, tmp);
	// }
	// }

	/**
	 * Handles the return code sent by the proxy agent
	 * 
	 * @param parameters
	 *            The parameters of the subscription
	 * @param request
	 *            The request object
	 */
	protected void handleCreationError(final Map<String, String> parameters, final CurlRequest request) {
		int exitCode = Integer.valueOf(request.getResponse());
		switch (exitCode) {
		case -1:
			throw new ValidationJsonException("The proxy agent doesn't reply");
		case 0:
			return;
		case 7:
			throw new ValidationJsonException("already-exist");
		case 8:
			throw new ValidationJsonException(parameterRepository, simpleName + "-repository",
					parameters.get(parameterRepository));
		default:
			throw new ValidationJsonException("Global");
		}
	}

	protected boolean handleExistenceError(Map<String, String> parameters, CurlRequest request) {
		int exitCode = Integer.valueOf(request.getResponse());
		switch (exitCode) {
		case -1:
			throw new ValidationJsonException("The proxy agent doesn't reply");
		case 0:
			return false;
		case 1:
			return true;
		default:
			throw new ValidationJsonException("Global");
		}

	}

}
