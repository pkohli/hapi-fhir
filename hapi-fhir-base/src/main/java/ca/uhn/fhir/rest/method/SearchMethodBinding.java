package ca.uhn.fhir.rest.method;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.dstu.valueset.RestfulOperationSystemEnum;
import ca.uhn.fhir.model.dstu.valueset.RestfulOperationTypeEnum;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.client.GetClientInvocation;
import ca.uhn.fhir.rest.param.IParameter;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.util.QueryUtil;

/**
 * Created by dsotnikov on 2/25/2014.
 */
public class SearchMethodBinding extends BaseMethodBinding {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(SearchMethodBinding.class);

	private Method myMethod;
	private Class<?> myDeclaredResourceType;
	private List<IParameter> myParameters;
	private String myQueryName;

	public SearchMethodBinding(MethodReturnTypeEnum theMethodReturnTypeEnum, Class<? extends IResource> theReturnResourceType, Method theMethod, String theQueryName) {
		super(theMethodReturnTypeEnum, theReturnResourceType);
		this.myMethod = theMethod;
		this.myParameters = Util.getResourceParameters(theMethod);
		this.myQueryName = StringUtils.defaultIfBlank(theQueryName, null);
		this.myDeclaredResourceType = theMethod.getReturnType();
	}

	public Class<?> getDeclaredResourceType() {
		return myDeclaredResourceType.getClass();
	}

	public Method getMethod() {
		return myMethod;
	}

	public List<IParameter> getParameters() {
		return myParameters;
	}

	@Override
	public ReturnTypeEnum getReturnType() {
		return ReturnTypeEnum.BUNDLE;
	}

	@Override
	public GetClientInvocation invokeClient(Object[] theArgs) throws InternalErrorException {
		 assert (myQueryName == null || ((theArgs != null ? theArgs.length : 0) == myParameters.size())) : "Wrong number of arguments: " + theArgs;

		Map<String, List<String>> args = new LinkedHashMap<String, List<String>>();

		if (myQueryName != null) {
			args.put(Constants.PARAM_QUERY, Collections.singletonList(myQueryName));
		}

		if (theArgs != null) {
			for (int idx = 0; idx < theArgs.length; idx++) {
				Object object = theArgs[idx];
				IParameter nextParam = myParameters.get(idx);

				if (object == null) {
					if (nextParam.isRequired()) {
						throw new NullPointerException("SearchParameter '" + nextParam.getName() + "' is required and may not be null");
					}
				} else {
					List<List<String>> value = nextParam.encode(object);
					ArrayList<String> paramValues = new ArrayList<String>(value.size());
					args.put(nextParam.getName(), paramValues);

					for (List<String> nextParamEntry : value) {
						StringBuilder b = new StringBuilder();
						for (String str : nextParamEntry) {
							if (b.length() > 0) {
								b.append(",");
							}
							b.append(str.replace(",", "\\,"));
						}
						paramValues.add(b.toString());
					}

				}

			}
		}

		return new GetClientInvocation(args, getResourceName());
	}

	@Override
	public List<IResource> invokeServer(IResourceProvider theResourceProvider, IdDt theId, IdDt theVersionId, Map<String, String[]> parameterValues) throws InvalidRequestException,
			InternalErrorException {
		assert theId == null;
		assert theVersionId == null;

		Object[] params = new Object[myParameters.size()];
		for (int i = 0; i < myParameters.size(); i++) {
			IParameter param = myParameters.get(i);
			String[] value = parameterValues.get(param.getName());
			if (value == null || value.length == 0) {
				if (param.handlesMissing()) {
					params[i] = param.parse(new ArrayList<List<String>>(0));
				}
				continue;
			}

			List<List<String>> paramList = new ArrayList<List<String>>(value.length);
			for (String nextParam : value) {
				if (nextParam.contains(",") == false) {
					paramList.add(Collections.singletonList(nextParam));
				} else {
					paramList.add(QueryUtil.splitQueryStringByCommasIgnoreEscape(nextParam));
				}
			}

			params[i] = param.parse(paramList);

		}

		Object response;
		try {
			response = this.myMethod.invoke(theResourceProvider, params);
		} catch (IllegalAccessException e) {
			throw new InternalErrorException(e);
		} catch (IllegalArgumentException e) {
			throw new InternalErrorException(e);
		} catch (InvocationTargetException e) {
			throw new InternalErrorException(e);
		}

		return toResourceList(response);

	}

	@Override
	public boolean matches(Request theRequest) {
		if (!theRequest.getResourceName().equals(getResourceName())) {
			ourLog.trace("Method {} doesn't match because resource name {} != {}", myMethod.getName(), theRequest.getResourceName(), getResourceName());
			return false;
		}
		if (theRequest.getId() != null || theRequest.getVersion() != null) {
			ourLog.trace("Method {} doesn't match because ID or Version are not null: {} - {}", theRequest.getId(), theRequest.getVersion());
			return false;
		}
		if (theRequest.getRequestType() == RequestType.GET && theRequest.getOperation() != null) {
			ourLog.trace("Method {} doesn't match because request type is GET but operation is not null: {}", theRequest.getId(), theRequest.getOperation());
			return false;
		}
		if (theRequest.getRequestType() == RequestType.POST && !"_search".equals(theRequest.getOperation())) {
			ourLog.trace("Method {} doesn't match because request type is POST but operation is not _search: {}", theRequest.getId(), theRequest.getOperation());
			return false;
		}

		Set<String> methodParamsTemp = new HashSet<String>();
		for (int i = 0; i < this.myParameters.size(); i++) {
			IParameter temp = this.myParameters.get(i);
			methodParamsTemp.add(temp.getName());
			if (temp.isRequired() && !theRequest.getParameterNames().containsKey(temp.getName())) {
				ourLog.trace("Method {} doesn't match param '{}' is not present", myMethod.getName(), temp.getName());
				return false;
			}
		}
		if (myQueryName != null) {
			String[] queryNameValues = theRequest.getParameterNames().get(Constants.PARAM_QUERY);
			if (queryNameValues != null && StringUtils.isNotBlank(queryNameValues[0])) {
				String queryName = queryNameValues[0];
				if (!myQueryName.equals(queryName)) {
					ourLog.trace("Query name does not match {}", myQueryName);
					return false;
				} else {
					methodParamsTemp.add(Constants.PARAM_QUERY);
				}
			} else {
				ourLog.trace("Query name does not match {}", myQueryName);
				return false;
			}
		}
		boolean retVal = methodParamsTemp.containsAll(theRequest.getParameterNames().keySet());

		ourLog.trace("Method {} matches: {}", myMethod.getName(), retVal);

		return retVal;
	}

	public void setMethod(Method method) {
		this.myMethod = method;
	}

	public void setParameters(List<IParameter> parameters) {
		this.myParameters = parameters;
	}

	public void setResourceType(Class<?> resourceType) {
		this.myDeclaredResourceType = resourceType;
	}

	public static enum RequestType {
		DELETE, GET, POST, PUT, OPTIONS
	}

	@Override
	public RestfulOperationTypeEnum getResourceOperationType() {
		return RestfulOperationTypeEnum.SEARCH_TYPE;
	}

	@Override
	public RestfulOperationSystemEnum getSystemOperationType() {
		return null;
	}

}