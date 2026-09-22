# Road data confidence v1

## Current path

The loaded GraphHopper road graph produces `surface`, `road_class`, and
`track_type` path details. `GraphHopperRoutingEngine.extractSegments`
splits geometry at their union of boundaries, decodes them to the existing
`Surface`, `HighwayType`, and `TrackType` enums, and calls
`RoadDataConfidenceResolver`. Each `RouteSegment` carries the result.
`Route.roadDataConfidenceSummary` aggregates its segments by exact
`RouteSegment.distanceMeters`.

## Evidence interpretation

GraphHopper road representation is treated as ROUTING_GRAPH evidence at medium
existence confidence. This means represented in the loaded routing graph, not
field verified or necessarily OSM sourced. The current phone graph contains
`bdot_source` and `synthetic_connector` encoded values and a
`bdot_surface.sqlite` file, so blanket OSM attribution would be false.
Secondary source support exists at the domain boundary; no per-segment
BDOT, ride trace, or imported dataset evidence is joined to routed segments
today.
Two explicitly supplied independent known sources yield high existence
confidence and the multi source method.

For surface, a recognized encoded surface value takes precedence and is
high confidence as a statement about the graph's encoded classification.
Its method is ENCODED_SURFACE_VALUE, not an assertion that the original OSM
tag survives import. Track type without surface yields medium confidence
for grade2 through grade5, classified UNPAVED. Grade1 can describe a solid
compacted track and cannot prove paved material; it produces UNKNOWN
classification/confidence with SECONDARY_ATTRIBUTE method. A broad off-road
road class alone yields UNKNOWN classification/confidence with the road
class method. Other missing metadata yields UNKNOWN evidence.
`Surface.UNKNOWN` is the only unknown classification representation.

Source sets are checked by evidence constructors. A known source cannot be
combined with UNKNOWN. The resolver normalizes incoming sets by removing
UNKNOWN when any known source is present.

## Compatibility

`RoadDataConfidence` is the authoritative segment evidence model. The old
`RouteSegment.dataConfidence` field was removed; a pre-TASK-008 JSON field
of that name is ignored by the saved route decoder. Missing
`roadDataConfidence` defaults to all UNKNOWN.

`DataConfidence` still serves the existing terrain radar and forest models.
`RouteMetrics.dataConfidenceScore` remains a compatibility metric used by
the existing route and loop code. Its former path-detail-presence calculation
is preserved through `RouteSegment.hasSurfaceOrRoadClassDetail` and is not
interpreted as evidence confidence. The radar similarly projects the old
display enum from this raw compatibility input.
Neither value supplies `RoadDataConfidence`.

## Data availability and graph limits

The current request asks GraphHopper for surface, road class and track type
path details. Smoothness is present in routing encoded values but is not
requested as a path detail or attached to segments. The weighting factory
optionally reads `bdot_source` and `synthetic_connector` encoded flags,
but segment extraction does not receive those flags. It cannot assert BDOT
provenance or corroboration. This is why runtime evidence uses ROUTING_GRAPH
rather than OSM. OSM way ID and original OSM tags are not present
in the path details; `RouteSegment.osmWayId` stays null. Ride traces exist
in the data layer but are not spatially joined to route segments.

A future graph change would need to retain the relevant attributes as
encoded values and expose them as path details, with segment boundaries
extended for those details. This task leaves the graph and routing weights
unchanged.

## Validation

Offline debug unit tests passed: domain 45, data 1, routing 86, navigation 6,
and app 12. The data test calls the production `RouteRepository.openRoute`
with a representative old JSON payload containing `dataConfidence` and
omitting `roadDataConfidence`. The debug APK built successfully. The final
APK was installed with data preservation on the connected SM-S938B primary
profile. A short A-to-B route produced a preview, and `Prowadź` entered
the riding screen. The test navigation was then ended.

## Aggregation

Exact segment distances are the only distance basis. A segment with UNKNOWN
surface classification belongs in the unknown surface bucket, including
broad road class fallback. Percentages are derived from distance and are
zero for a zero-distance route.
