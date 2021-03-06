/**
 * Copyright (c) 2015-present Jorge Díaz All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package jorgediazest.indexchecker.portlet;

import com.liferay.portal.kernel.dao.orm.Conjunction;
import com.liferay.portal.kernel.dao.orm.Disjunction;
import com.liferay.portal.kernel.dao.orm.DynamicQuery;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.dao.orm.RestrictionsFactoryUtil;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.PrefsPropsUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.model.Company;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.security.auth.CompanyThreadLocal;
import com.liferay.portal.kernel.service.ClassNameLocalServiceUtil;
import com.liferay.portal.kernel.service.CompanyLocalServiceUtil;
import com.liferay.portal.kernel.service.GroupLocalServiceUtil;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.document.library.kernel.model.DLFileEntry;
import com.liferay.portal.kernel.portlet.bridges.mvc.MVCPortlet;
import com.liferay.util.portlet.PortletProps;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.PortletException;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;

import jorgediazest.indexchecker.ExecutionMode;
import jorgediazest.indexchecker.data.DataIndexCheckerModelComparator;
import jorgediazest.indexchecker.data.DataIndexCheckerResourceModelComparator;
import jorgediazest.indexchecker.index.IndexSearchUtil;
import jorgediazest.indexchecker.model.IndexCheckerModel;
import jorgediazest.indexchecker.model.IndexCheckerModelFactory;

import jorgediazest.util.data.Comparison;
import jorgediazest.util.data.ComparisonUtil;
import jorgediazest.util.data.Data;
import jorgediazest.util.data.DataComparator;
import jorgediazest.util.model.Model;
import jorgediazest.util.model.ModelFactory;
import jorgediazest.util.model.ModelFactory.DataComparatorFactory;
import jorgediazest.util.model.ModelUtil;

/**
 * Portlet implementation class IndexCheckerPortlet
 *
 * @author Jorge Díaz
 */
public class IndexCheckerPortlet extends MVCPortlet {

	public static Map<Long, List<Comparison>> executeCheck(
		Company company, List<Long> groupIds, List<String> classNames,
		Set<ExecutionMode> executionMode, int threadsExecutor)
	throws ExecutionException, InterruptedException, SystemException {

		ModelFactory modelFactory = new IndexCheckerModelFactory();

		final String dateAttributes = PortletProps.get(
			"data-comparator.date.attributes");
		final String dateAttributesUser = PortletProps.get(
			"data-comparator.date.attributes.user");
		final String basicAttributes = PortletProps.get(
			"data-comparator.basic.attributes");
		final String basicAttributesNoVersion = PortletProps.get(
			"data-comparator.basic.attributes.noversion");
		final String categoriesTagsAttributes = PortletProps.get(
			"data-comparator.categories-tags.attributes");
		final String assetEntryAttributes = PortletProps.get(
			"data-comparator.assetentry.attributes");

		DataComparatorFactory dataComparatorFactory =
			new DataComparatorFactory() {

			protected boolean indexAllVersions =
				PrefsPropsUtil.getBoolean(
					"journal.articles.index.all.versions");

			protected DataComparator defaultComparator =
				new DataIndexCheckerModelComparator(
					(dateAttributes + "," + basicAttributes + "," +
						assetEntryAttributes + "," +
						categoriesTagsAttributes).split(","));

			protected DataComparator userComparator =
				new DataIndexCheckerModelComparator(
					(dateAttributesUser + "," + basicAttributes + "," +
						categoriesTagsAttributes).split(","));

			protected DataComparator dlFileEntryComparator =
				new DataIndexCheckerModelComparator(
					(dateAttributes + "," + basicAttributesNoVersion + "," +
						assetEntryAttributes + "," +
							categoriesTagsAttributes).split(","));

			protected DataComparator wikiPageComparator =
				new DataIndexCheckerResourceModelComparator(
					(dateAttributes + "," + basicAttributesNoVersion + "," +
						assetEntryAttributes + "," +
							categoriesTagsAttributes).split(","));

			protected DataComparator resourceComparator =
				new DataIndexCheckerResourceModelComparator(
					(dateAttributes + "," + basicAttributes + "," +
						assetEntryAttributes + "," +
						categoriesTagsAttributes).split(","));

			@Override
			public DataComparator getDataComparator(Model model) {
				if ("com.liferay.journal.model.JournalArticle".equals(
						model.getClassName()) && indexAllVersions) {

					return defaultComparator;
				}
				else if (User.class.getName().equals(model.getClassName())) {
					return userComparator;
				}
				else if (DLFileEntry.class.getName().equals(
							model.getClassName())) {

					return dlFileEntryComparator;
				}
				else if ("com.liferay.wiki.model.WikiPage".equals(
							model.getClassName())) {

					return wikiPageComparator;
				}

				if (model.isResourcedModel()) {
					return resourceComparator;
				}

				return defaultComparator;
			}

		};

		modelFactory.setDataComparatorFactory(dataComparatorFactory);

		Map<String, Model> modelMap = modelFactory.getModelMap(classNames);

		IndexSearchUtil.autoAdjustIndexSearchLimit(modelMap.values());

		long companyId = company.getCompanyId();

		List<Long> groupIdsFor = new ArrayList<Long>();
		groupIdsFor.add(0L);

		if (executionMode.contains(ExecutionMode.GROUP_BY_SITE)) {
			groupIdsFor.addAll(groupIds);
		}

		ExecutorService executor = Executors.newFixedThreadPool(
			threadsExecutor);

		Map<Long, List<Future<Comparison>>> futureResultDataMap =
			new LinkedHashMap<Long, List<Future<Comparison>>>();

		for (long groupId : groupIdsFor) {
			List<Future<Comparison>> futureResultList =
				new ArrayList<Future<Comparison>>();

			for (Model model : modelMap.values()) {
				if (!model.hasIndexerEnabled()) {
					continue;
				}

				CallableCheckGroupAndModel c =
					new CallableCheckGroupAndModel(
						companyId, groupId, (IndexCheckerModel)model,
						executionMode);

				futureResultList.add(executor.submit(c));
			}

			futureResultDataMap.put(groupId, futureResultList);
		}

		Map<Long, List<Comparison>> resultDataMap =
			new LinkedHashMap<Long, List<Comparison>>();

		for (
			Entry<Long, List<Future<Comparison>>> entry :
				futureResultDataMap.entrySet()) {

			List<Comparison> resultList = new ArrayList<Comparison>();

			for (Future<Comparison> f : entry.getValue()) {
				Comparison results = f.get();

				if (results != null) {
					resultList.add(results);
				}
			}

			resultDataMap.put(entry.getKey(), resultList);
		}

		executor.shutdownNow();

		return resultDataMap;
	}

	public static EnumSet<ExecutionMode>
		getExecutionMode(ActionRequest request) {

		EnumSet<ExecutionMode> executionMode = EnumSet.noneOf(
			ExecutionMode.class);

		if (ParamUtil.getBoolean(request, "outputGroupBySite")) {
			executionMode.add(ExecutionMode.GROUP_BY_SITE);
		}

		if (ParamUtil.getBoolean(request, "outputBothExact")) {
			executionMode.add(ExecutionMode.SHOW_BOTH_EXACT);
		}

		if (ParamUtil.getBoolean(request, "outputBothNotExact")) {
			executionMode.add(ExecutionMode.SHOW_BOTH_NOTEXACT);
		}

		if (ParamUtil.getBoolean(request, "outputLiferay")) {
			executionMode.add(ExecutionMode.SHOW_LIFERAY);
		}

		if (ParamUtil.getBoolean(request, "outputIndex")) {
			executionMode.add(ExecutionMode.SHOW_INDEX);
		}

		if (ParamUtil.getBoolean(request, "dumpAllObjectsToLog")) {
			executionMode.add(ExecutionMode.DUMP_ALL_OBJECTS_TO_LOG);
		}

		return executionMode;
	}

	public static Log getLogger() {
		return _log;
	}

	public static Map<Data, String> reindex(Comparison comparison) {

		Set<Data> objectsToReindex = new HashSet<Data>();

		for (String type : comparison.getOutputTypes()) {
			if (!type.endsWith("-right")) {
				Set<Data> aux = comparison.getData(type);

				if (aux != null) {
					objectsToReindex.addAll(aux);
				}
			}
		}

		IndexCheckerModel model = (IndexCheckerModel)comparison.getModel();

		return model.reindex(objectsToReindex);
	}

	public static Map<Data, String> removeIndexOrphans(Comparison comparison) {
		Set<Data> indexOnlyData = comparison.getData("only-right");

		if ((indexOnlyData == null) || indexOnlyData.isEmpty()) {
			return null;
		}

		IndexCheckerModel model = (IndexCheckerModel)comparison.getModel();

		return model.deleteAndCheck(indexOnlyData);
	}

	public void doView(
		RenderRequest renderRequest, RenderResponse renderResponse)
			throws IOException, PortletException {

		String filterGroupId = ParamUtil.getString(
			renderRequest, "filterGroupId");

		if (Validator.isNull(filterGroupId)) {
			List<Long> siteGroupIds = this.getSiteGroupIds();

			filterGroupId = "";

			for (Long groupId : siteGroupIds) {
				if (Validator.isNotNull(filterGroupId)) {
					filterGroupId += ",";
				}

				filterGroupId += groupId;
			}
		}

		renderRequest.setAttribute("filterGroupId", filterGroupId);

		int numberOfThreads = getNumberOfThreads(renderRequest);
		renderRequest.setAttribute("numberOfThreads", numberOfThreads);

		super.doView(renderRequest, renderResponse);
	}

	public void executeCheck(ActionRequest request, ActionResponse response)
		throws Exception {

		PortalUtil.copyRequestParameters(request, response);

		EnumSet<ExecutionMode> executionMode = getExecutionMode(request);

		String[] filterClassNameArr = null;
		String filterClassName = ParamUtil.getString(
			request, "filterClassName");

		if (Validator.isNotNull(filterClassName)) {
			filterClassNameArr = filterClassName.split(",");
		}

		String[] filterGroupIdArr = null;
		String filterGroupId = ParamUtil.getString(request, "filterGroupId");

		if (Validator.isNotNull(filterGroupId)) {
			filterGroupIdArr = filterGroupId.split(",");
		}

		List<Company> companies = CompanyLocalServiceUtil.getCompanies();

		Map<Company, Map<Long, List<Comparison>>> companyResultDataMap =
			new HashMap<Company, Map<Long, List<Comparison>>>();

		Map<Company, Long> companyProcessTime = new HashMap<Company, Long>();

		Map<Company, String> companyError = new HashMap<Company, String>();

		for (Company company : companies) {
			try {
				CompanyThreadLocal.setCompanyId(company.getCompanyId());

				List<String> classNames = getClassNames(filterClassNameArr);

				List<Long> groupIds = getGroupIds(company, filterGroupIdArr);

				long startTime = System.currentTimeMillis();

				Map<Long, List<Comparison>> resultDataMap =
					IndexCheckerPortlet.executeCheck(
						company, groupIds, classNames, executionMode,
						getNumberOfThreads(request));

				long endTime = System.currentTimeMillis();

				if (_log.isInfoEnabled() &&
					executionMode.contains(
							ExecutionMode.DUMP_ALL_OBJECTS_TO_LOG)) {

					_log.info("COMPANY: " + company);

					boolean groupBySite = executionMode.contains(
						ExecutionMode.GROUP_BY_SITE);

					ComparisonUtil.dumpToLog(groupBySite, resultDataMap);
				}

				companyResultDataMap.put(company, resultDataMap);

				companyProcessTime.put(company, (endTime - startTime));
			}
			catch (Throwable t) {
				StringWriter swt = new StringWriter();
				PrintWriter pwt = new PrintWriter(swt);
				pwt.println("Error during execution: " + t.getMessage());
				t.printStackTrace(pwt);
				companyError.put(company, swt.toString());
				_log.error(t, t);
			}
		}

		request.setAttribute("title", "Check Index");
		request.setAttribute("executionMode", executionMode);
		request.setAttribute("companyProcessTime", companyProcessTime);
		request.setAttribute("companyResultDataMap", companyResultDataMap);
		request.setAttribute("companyError", companyError);
	}

	public void executeReindex(ActionRequest request, ActionResponse response)
		throws Exception {

		PortalUtil.copyRequestParameters(request, response);

		EnumSet<ExecutionMode> executionMode = getExecutionMode(request);

		String[] filterClassNameArr = null;
		String filterClassName = ParamUtil.getString(
			request, "filterClassName");

		if (Validator.isNotNull(filterClassName)) {
			filterClassNameArr = filterClassName.split(",");
		}

		String[] filterGroupIdArr = null;
		String filterGroupId = ParamUtil.getString(request, "filterGroupId");

		if (Validator.isNotNull(filterGroupId)) {
			filterGroupIdArr = filterGroupId.split(",");
		}

		List<Company> companies = CompanyLocalServiceUtil.getCompanies();

		Map<Company, Long> companyProcessTime = new HashMap<Company, Long>();

		Map<Company, String> companyError = new HashMap<Company, String>();

		for (Company company : companies) {
			StringWriter sw = new StringWriter();

			PrintWriter pw = new PrintWriter(sw);

			try {
				List<String> classNames = getClassNames(filterClassNameArr);

				List<Long> groupIds = getGroupIds(company, filterGroupIdArr);

				long startTime = System.currentTimeMillis();

				Map<Long, List<Comparison>> resultDataMap =
					IndexCheckerPortlet.executeCheck(
						company, groupIds, classNames, executionMode,
						getNumberOfThreads(request));

				for (
					Entry<Long, List<Comparison>> entry :
						resultDataMap.entrySet()) {

					List<Comparison> resultList = entry.getValue();

					for (Comparison result : resultList) {
						Map<Data, String> errors = reindex(result);
/* TODO Mover todo esto al JSP */
						if (((errors!= null) && (errors.size() > 0)) ||
							(result.getError() != null)) {

							pw.println();
							pw.println("----");
							pw.println(result.getModel().getName());
							pw.println("----");

							for (Entry<Data, String> e : errors.entrySet()) {
								pw.println(
									" * " + e.getKey() + " - Exception: " +
										e.getValue());
							}

							pw.println(" * " + result.getError());
						}
					}
				}

				long endTime = System.currentTimeMillis();

				companyProcessTime.put(company, endTime - startTime);
			}
			catch (Throwable t) {
				StringWriter swt = new StringWriter();
				PrintWriter pwt = new PrintWriter(swt);
				pwt.println("Error during execution: " + t.getMessage());
				t.printStackTrace(pwt);
				companyError.put(company, swt.toString());
				_log.error(t, t);
			}

			companyError.put(company, sw.toString());
		}

		request.setAttribute("title", "Reindex");
		request.setAttribute("executionMode", executionMode);
		request.setAttribute("companyProcessTime", companyProcessTime);
		request.setAttribute("companyError", companyError);
	}

	public void executeRemoveOrphans(
			ActionRequest request, ActionResponse response)
		throws Exception {

		PortalUtil.copyRequestParameters(request, response);

		EnumSet<ExecutionMode> executionMode = getExecutionMode(request);

		String[] filterClassNameArr = null;
		String filterClassName = ParamUtil.getString(
			request, "filterClassName");

		if (Validator.isNotNull(filterClassName)) {
			filterClassNameArr = filterClassName.split(",");
		}

		String[] filterGroupIdArr = null;
		String filterGroupId = ParamUtil.getString(request, "filterGroupId");

		if (Validator.isNotNull(filterGroupId)) {
			filterGroupIdArr = filterGroupId.split(",");
		}

		List<Company> companies = CompanyLocalServiceUtil.getCompanies();

		Map<Company, Long> companyProcessTime = new HashMap<Company, Long>();

		Map<Company, String> companyError = new HashMap<Company, String>();

		for (Company company : companies) {
			StringWriter sw = new StringWriter();

			PrintWriter pw = new PrintWriter(sw);

			try {
				List<String> classNames = getClassNames(filterClassNameArr);

				List<Long> groupIds = getGroupIds(company, filterGroupIdArr);

				long startTime = System.currentTimeMillis();

				Map<Long, List<Comparison>> resultDataMap =
					IndexCheckerPortlet.executeCheck(
						company, groupIds, classNames, executionMode,
						getNumberOfThreads(request));

				for (
					Entry<Long, List<Comparison>> entry :
						resultDataMap.entrySet()) {

					List<Comparison> resultList = entry.getValue();

					for (Comparison result : resultList) {
						Map<Data, String> errors = removeIndexOrphans(result);
						/* TODO Mover todo esto al JSP */
						if (((errors != null) && (errors.size() > 0)) ||
							(result.getError() != null)) {

							pw.println();
							pw.println("----");
							pw.println(result.getModel().getName());
							pw.println("----");

							for (Entry<Data, String> e : errors.entrySet()) {
								pw.println(
									" * " + e.getKey() + " - Exception: " +
										e.getValue());
							}

							pw.println(" * " + result.getError());
						}
					}
				}

				long endTime = System.currentTimeMillis();

				companyProcessTime.put(company, endTime - startTime);
			}
			catch (Throwable t) {
				StringWriter swt = new StringWriter();
				PrintWriter pwt = new PrintWriter(swt);
				pwt.println("Error during execution: " + t.getMessage());
				t.printStackTrace(pwt);
				companyError.put(company, swt.toString());
				_log.error(t, t);
			}

			companyError.put(company, sw.toString());
		}

		request.setAttribute("title", "Remove index orphan");
		request.setAttribute("executionMode", executionMode);
		request.setAttribute("companyProcessTime", companyProcessTime);
		request.setAttribute("companyError", companyError);
	}

	public List<String> getClassNames() throws SystemException {
		return getClassNames(null);
	}

	public List<String> getClassNames(String[] filterClassNameArr)
		throws SystemException {

		List<String> allClassName =
			ModelUtil.getClassNameValues(
				ClassNameLocalServiceUtil.getClassNames(
					QueryUtil.ALL_POS, QueryUtil.ALL_POS));

		List<String> classNames = new ArrayList<String>();

		for (String className : allClassName) {
			if (ignoreClassName(className)) {
				continue;
			}

			if (filterClassNameArr == null) {
				classNames.add(className);
				continue;
			}

			for (String filterClassName : filterClassNameArr) {
				if (className.contains(filterClassName)) {
					classNames.add(className);
					break;
				}
			}
		}

		return classNames;
	}

	public List<Long> getGroupIds(Company company, String[] filterGroupIdArr)
		throws SystemException {

		List<Group> groups =
			GroupLocalServiceUtil.getCompanyGroups(
				company.getCompanyId(), QueryUtil.ALL_POS, QueryUtil.ALL_POS);

		List<Long> groupIds = new ArrayList<Long>();

		for (Group group : groups) {
			if (filterGroupIdArr == null) {
				groupIds.add(group.getGroupId());
				continue;
			}

			String groupIdStr = "" + group.getGroupId();

			for (int i = 0; i < filterGroupIdArr.length; i++) {
				if (groupIdStr.equals(filterGroupIdArr[i])) {
					groupIds.add(group.getGroupId());
					break;
				}
			}
		}

		return groupIds;
	}

	public int getNumberOfThreads(ActionRequest actionRequest) {
		int def = GetterUtil.getInteger(PortletProps.get("number.threads"),1);

		int num = ParamUtil.getInteger(actionRequest, "numberOfThreads", def);

		return (num == 0) ? def : num;
	}

	public int getNumberOfThreads(RenderRequest renderRequest) {
		int def = GetterUtil.getInteger(PortletProps.get("number.threads"), 1);

		int num = ParamUtil.getInteger(renderRequest, "numberOfThreads", def);

		return (num == 0) ? def : num;
	}

	@SuppressWarnings("unchecked")
	public List<Long> getSiteGroupIds() {

		long companyClassNameId = PortalUtil.getClassNameId(Company.class);

		ModelFactory modelFactory = new IndexCheckerModelFactory();

		Model model = modelFactory.getModelObject(Group.class);

		if (model == null) {
			return new ArrayList<Long>();
		}

		DynamicQuery groupDynamicQuery = model.getService().newDynamicQuery();

		Conjunction stagingSites = RestrictionsFactoryUtil.conjunction();
		stagingSites.add(model.getProperty("site").eq(false));
		stagingSites.add(model.getProperty("liveGroupId").ne(0L));

		Conjunction normalSites = RestrictionsFactoryUtil.conjunction();
		normalSites.add(model.getProperty("site").eq(true));
		normalSites.add(
			model.getProperty("classNameId").ne(companyClassNameId));

		Disjunction disjunction = RestrictionsFactoryUtil.disjunction();
		disjunction.add(stagingSites);
		disjunction.add(normalSites);

		groupDynamicQuery.add(disjunction);
		groupDynamicQuery.setProjection(model.getPropertyProjection("groupId"));

		try {
			return (List<Long>)model.getService().executeDynamicQuery(
				groupDynamicQuery);
		}
		catch (Exception e) {
			if (_log.isWarnEnabled()) {
				_log.warn(e, e);
			}

			return new ArrayList<Long>();
		}
	}

	public boolean ignoreClassName(String className) {
		if (Validator.isNull(className)) {
			return true;
		}

		for (String ignoreClassName : ignoreClassNames) {
			if (ignoreClassName.equals(className)) {
				return true;
			}
		}

		return false;
	}

	public void init() throws PortletException {
		super.init();

		try {
			ModelFactory modelFactory = new IndexCheckerModelFactory();

			Map<String, Model> modelMap = modelFactory.getModelMap(
				getClassNames());

			IndexSearchUtil.autoAdjustIndexSearchLimit(modelMap.values());
		}
		catch (Exception e) {
			_log.error(e, e);
		}
	}

	private static Log _log = LogFactoryUtil.getLog(IndexCheckerPortlet.class);

	private static String[] ignoreClassNames = new String[] {
		"com.liferay.portal.kernel.repository.model.FileEntry",
		"com.liferay.portal.kernel.repository.model.Folder",
		"com.liferay.portal.model.UserPersonalSite"};

}