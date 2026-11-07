FROM docker.elastic.co/elasticsearch/elasticsearch:8.17.0

# IK Chinese analyzer. If the download fails the build still succeeds and the
# backend falls back to the standard analyzer when creating the index.
RUN bin/elasticsearch-plugin install --batch \
    https://get.infini.cloud/elasticsearch/analysis-ik/8.17.0 \
    || echo "IK plugin unavailable; standard analyzer fallback"
