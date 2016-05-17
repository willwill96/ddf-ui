package org.codice.ddf.catalog.ui.metacard.associations;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.codice.ddf.ui.searchui.standard.endpoints.EndpointUtil;

import ddf.catalog.CatalogFramework;
import ddf.catalog.data.Attribute;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.Result;
import ddf.catalog.federation.FederationException;
import ddf.catalog.source.SourceUnavailableException;
import ddf.catalog.source.UnsupportedQueryException;

public class Associated {
    // TODO (RCZ) - this is yucky and i dont' really like ittttttt
    // TODO (RCZ) - this is yucky and i dont' really like ittttttt
    // TODO (RCZ) - this is yucky and i dont' really like ittttttt
    // TODO (RCZ) - this is yucky and i dont' really like ittttttt
    private static final String ASSOCIATION_PREFIX = "metacard.associations.";

    private List<Response> response;

    private Associated() {
    }

    public static Associated getAssociatedItems(Metacard metacard, EndpointUtil util)
            throws UnsupportedQueryException, SourceUnavailableException, FederationException {
        AssociationStruct associatedIds = getAssociatedMetacardIdsFromMetacard(metacard);
        Associated associated = new Associated();
        associated.setResponse(getAssociationsResponse(associatedIds, util));
        return associated;
    }

    public static Associated putAssociatedItems(List<Response> responses, Metacard target,
            CatalogFramework framework) {
        return null;
    }

    public List<Response> getResponse() {
        return response;
    }

    public void setResponse(List<Response> response) {
        this.response = response;
    }

    private static AssociationStruct getAssociatedMetacardIdsFromMetacard(Metacard result) {
        List<Serializable> related = new ArrayList<>();
        List<Serializable> derived = new ArrayList<>();

        Attribute relatedAttribute = result.getAttribute(Metacard.RELATED);
        related = relatedAttribute != null ? relatedAttribute.getValues() : related;

        Attribute derivedAttribute = result.getAttribute(Metacard.DERIVED);
        derived = derivedAttribute != null ? derivedAttribute.getValues() : derived;

        AssociationStruct associated = new AssociationStruct();
        associated.related = getStringList(related);
        associated.derived = getStringList(derived);

        return associated;
    }

    private static List<Response> getAssociationsResponse(AssociationStruct associated,
            EndpointUtil util)
            throws UnsupportedQueryException, SourceUnavailableException, FederationException {
        List<String> ids = new ArrayList<>();
        ids.addAll(associated.derived);
        ids.addAll(associated.related);

        Map<String, Result> results = util.getMetacards(ids, "*");

        Response relatedAssociations = new Response();
        relatedAssociations.type = "related";
        for (String relatedId : associated.related) {
            relatedAssociations.metacards.add(new Item(relatedId,
                    results.get(relatedId)
                            .getMetacard()
                            .getTitle()));
        }

        Response derivedAssociations = new Response();
        derivedAssociations.type = "derived";
        for (String derivedId : associated.derived) {
            derivedAssociations.metacards.add(new Item(derivedId,
                    results.get(derivedId)
                            .getMetacard()
                            .getTitle()));
        }

        List<Response> associations = new ArrayList<>();
        associations.add(derivedAssociations);
        associations.add(relatedAssociations);
        return associations;
    }

    private static List<String> getStringList(List<Serializable> list) {
        if (list == null) {
            return new ArrayList<>();
        }
        return list.stream()
                .map(String::valueOf)
                .collect(Collectors.toList());
    }

    public static class Response {
        private String type;

        private List<Item> metacards = new ArrayList<>();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public List<Item> getMetacards() {
            return metacards;
        }

        public void setMetacards(List<Item> metacards) {
            this.metacards = metacards;
        }
    }

    public static class Item {
        private String id;

        private String title;

        private Item(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    private static class AssociationStruct {
        List<String> related;

        List<String> derived;
    }
}
