package org.codice.ddf.catalog.ui.metacard;

import static org.codice.ddf.ui.searchui.standard.endpoints.EndpointUtil.compareBy;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.patch;
import static spark.Spark.post;
import static spark.Spark.put;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.ws.rs.NotFoundException;

import org.apache.shiro.SecurityUtils;
import org.boon.json.JsonFactory;
import org.codice.ddf.catalog.ui.metacard.edit.AttributeChange;
import org.codice.ddf.catalog.ui.metacard.edit.MetacardChanges;
import org.codice.ddf.catalog.ui.metacard.history.HistoryResponse;
import org.codice.ddf.catalog.ui.metacard.validation.Validator;
import org.codice.ddf.ui.searchui.standard.endpoints.EndpointUtil;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;

import ddf.catalog.CatalogFramework;
import ddf.catalog.core.versioning.HistoryMetacardImpl;
import ddf.catalog.data.AttributeDescriptor;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.Result;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.federation.FederationException;
import ddf.catalog.filter.FilterBuilder;
import ddf.catalog.operation.DeleteResponse;
import ddf.catalog.operation.QueryResponse;
import ddf.catalog.operation.UpdateResponse;
import ddf.catalog.operation.impl.DeleteRequestImpl;
import ddf.catalog.operation.impl.QueryImpl;
import ddf.catalog.operation.impl.QueryRequestImpl;
import ddf.catalog.operation.impl.UpdateRequestImpl;
import ddf.catalog.source.IngestException;
import ddf.catalog.source.SourceUnavailableException;
import ddf.catalog.source.UnsupportedQueryException;
import ddf.security.SubjectUtils;
import spark.servlet.SparkApplication;

public class MetacardApplication implements SparkApplication {
    private final CatalogFramework catalogFramework;

    private final FilterBuilder filterBuilder;

    private final EndpointUtil util;

    private final Validator validator;

    public MetacardApplication(CatalogFramework catalogFramework, FilterBuilder filterBuilder,
            EndpointUtil endpointUtil, Validator validator) {
        this.catalogFramework = catalogFramework;
        this.filterBuilder = filterBuilder;
        this.util = endpointUtil;
        this.validator = validator;
    }

    @Override
    public void init() {
        get("/metacardtype", (req, res) -> {
            res.type(APPLICATION_JSON);
            return util.getJson(util.getMetacardTypeMap());
        });

        get("/metacard/:id", (req, res) -> {
            String id = req.params(":id");
            res.type(APPLICATION_JSON);
            return util.metacardToJson(id);
        });

        get("/metacard/:id/validation", (req, res) -> {
            String id = req.params(":id");
            res.type(APPLICATION_JSON);
            return validator.getValidation(util.getMetacard(id));
        });

        post("/metacards", APPLICATION_JSON, (req, res) -> {
            List<String> ids = JsonFactory.create()
                    .parser()
                    .parseList(String.class, req.body());
            List<Metacard> metacards = util.getMetacards(ids, "*")
                    .entrySet()
                    .stream()
                    .map(Map.Entry::getValue)
                    .map(Result::getMetacard)
                    .collect(Collectors.toList());

            res.type(APPLICATION_JSON);
            return util.metacardsToJson(metacards);
        });

        delete("/metacards", APPLICATION_JSON, (req, res) -> {
            List<String> ids = JsonFactory.create()
                    .parser()
                    .parseList(String.class, req.body());
            DeleteResponse deleteResponse = catalogFramework.delete(new DeleteRequestImpl(ids,
                    Metacard.ID,
                    null));
            if (deleteResponse.getProcessingErrors() != null
                    && !deleteResponse.getProcessingErrors()
                    .isEmpty()) {
                res.status(500);
                return "";
            }
            // TODO (RCZ) - wat do.
            return "";
        });

        patch("/metacards", APPLICATION_JSON, (req, res) -> {
            List<MetacardChanges> metacardChanges = JsonFactory.create()
                    .parser()
                    .parseList(MetacardChanges.class, req.body());

            UpdateResponse updateResponse = patchMetacards(metacardChanges);

            if (updateResponse.getProcessingErrors() != null
                    && !updateResponse.getProcessingErrors()
                    .isEmpty()) {
                res.status(500);
                return updateResponse.getProcessingErrors();
            }

            return req.body();
        });

        get("/metacards/recent", (req, res) -> {
            int pageSize = Integer.parseInt(req.queryParams("pageSize"));
            int pageNumber = Integer.parseInt(req.queryParams("pageNumber"));

            List<Metacard> results = util.getRecentMetacards(pageSize,
                    pageNumber,
                    SubjectUtils.getEmailAddress(SecurityUtils.getSubject()));
            return util.getJson(results);
        });

        put("/validate/attribute/:attribute", TEXT_PLAIN, (req, res) -> {
            String attribute = req.params(":attribute");
            String value = req.body();
            return util.getJson(validator.validateAttribute(attribute, value));
        });

        // TODO (RCZ) - this could use some help
        get("/history/:id", (req, res) -> {
            String id = req.params(":id");
            List<Result> queryResponse = getMetacardHistory(id);
            if (queryResponse == null || queryResponse.isEmpty()) {
                throw new NotFoundException("Could not find metacard with id: " + id);
            }
            List<HistoryResponse> response = queryResponse.stream()
                    .map(Result::getMetacard)
                    .map(mc -> new HistoryResponse(mc.getId(),
                            (String) mc.getAttribute(HistoryMetacardImpl.EDITED_BY)
                                    .getValue(),
                            (Date) mc.getAttribute(HistoryMetacardImpl.VERSIONED)
                                    .getValue()))
                    .sorted(compareBy(HistoryResponse::getVersioned))
                    .collect(Collectors.toList());
            res.type(APPLICATION_JSON);
            return util.getJson(response);
        });

        get("/history/:id/revert/:revertid", (req, res) -> {
            String id = req.params(":id");
            String revertId = req.params(":revertid");

            Metacard versionMetacard = util.getMetacard(revertId);
            if (versionMetacard.getAttribute(HistoryMetacardImpl.ACTION)
                    .getValue()
                    .equals(HistoryMetacardImpl.Action.DELETED.getKey())) {
            /* can't revert to a deleted.. right now */
                res.status(400);
                return "";
            }
            Metacard revertMetacard = HistoryMetacardImpl.toBasicMetacard(versionMetacard);
            catalogFramework.update(new UpdateRequestImpl(id, revertMetacard));
            return util.metacardToJson(revertMetacard);
        });

    }

    protected UpdateResponse patchMetacards(List<MetacardChanges> metacardChanges)
            throws SourceUnavailableException, IngestException, FederationException,
            UnsupportedQueryException {
        Set<String> changedIds = metacardChanges.stream()
                .flatMap(mc -> mc.getIds()
                        .stream())
                .collect(Collectors.toSet());

        Map<String, Result> results = util.getMetacards(changedIds, "*");

        for (MetacardChanges changeset : metacardChanges) {
            for (AttributeChange attributeChange : changeset.getAttributes()) {
                for (String id : changeset.getIds()) {
                    Result result = results.get(id);
                    if (Optional.ofNullable(result)
                            .map(Result::getMetacard)
                            .map(Metacard::getMetacardType)
                            .map(mt -> mt.getAttributeDescriptor(attributeChange.getAttribute()))
                            .map(AttributeDescriptor::isMultiValued)
                            .orElse(false)) {
                        result.getMetacard()
                                .setAttribute(new AttributeImpl(attributeChange.getAttribute(),
                                        (List<Serializable>) new ArrayList<Serializable>(
                                                attributeChange.getValues())));
                    } else {
                        result.getMetacard()
                                .setAttribute(new AttributeImpl(attributeChange.getAttribute(),
                                        Collections.singletonList(attributeChange.getValues()
                                                .get(0))));
                    }
                }
            }
        }

        List<Metacard> changedMetacards = results.values()
                .stream()
                .map(Result::getMetacard)
                .collect(Collectors.toList());
        return catalogFramework.update(new UpdateRequestImpl(changedIds.toArray(new String[0]),
                changedMetacards));
    }

    private List<Result> getMetacardHistory(String id) throws Exception {
        Filter historyFilter = filterBuilder.attribute(Metacard.TAGS)
                .is()
                .equalTo()
                .text(HistoryMetacardImpl.HISTORY_TAG);
        Filter idFilter = filterBuilder.attribute(HistoryMetacardImpl.ID_HISTORY)
                .is()
                .equalTo()
                .text(id);

        Filter filter = filterBuilder.allOf(historyFilter, idFilter);
        QueryResponse response = catalogFramework.query(new QueryRequestImpl(new QueryImpl(filter,
                1,
                -1,
                SortBy.NATURAL_ORDER,
                false,
                TimeUnit.SECONDS.toMillis(10)), false));
        return response.getResults();
    }
}
