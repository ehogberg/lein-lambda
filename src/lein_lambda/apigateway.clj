(ns lein-lambda.apigateway
  (:require [amazonica.aws.apigateway :as amazon]
            [lein-lambda.lambda :as lambda]))

(defn- target-arn [function-arn region]
  (str "arn:aws:apigateway:" 
       region
       ":lambda:path/2015-03-31/functions/"
       function-arn
       "/invocations"))

(defn- source-arn [api-id region account-id]
  (str "arn:aws:execute-api:"
       region
       ":"
       account-id
       ":"
       api-id
       "/*/*/*"))

(defn- find-api [name]
  (:id (let [apis ((amazon/get-rest-apis :limit 500) :items)]
         (first (filter #(get % :name) apis)))))

(defn- create-api [name function-name region account-id]
  (println "Creating API:" name)
  (let [api-id (:id (amazon/create-rest-api :name name))]
    (lambda/allow-api-gateway function-name
                              (source-arn api-id region account-id))
    api-id))

(defn- maybe-create-api [name function-name region account-id]
  (or
    (find-api name)
    (create-api name function-name region account-id)))

(def root-path "/")
(def proxy-path-part "{proxy+}")
(def proxy-path (str root-path proxy-path-part))

(defn- find-path [path resources]
  (some->> resources
    (filter #(= path (% :path)))
    (first)
    (:id)))

(defn- get-resource-ids [api-id]
  (let [resources ((amazon/get-resources :restapi-id api-id) :items)]
    [(find-path root-path resources)
     (find-path proxy-path resources)]))

(defn- create-proxy-resource [api-id root-id]
  (println "Creating proxy resource")
  (:id (amazon/create-resource :restapi-id api-id
                               :parent-id root-id
                               :path-part proxy-path-part)))

(defn- maybe-create-proxy-resource [api-id root-id proxy-id]
  (or proxy-id
    (create-proxy-resource api-id root-id)))

(def http-method "ANY")

(defn- find-method [api-id proxy-id]
  (try
    (amazon/get-method :http-method http-method
                       :resource-id proxy-id
                       :restapi-id api-id)
    (catch Exception _ false)))

(defn- create-method [api-id proxy-id]
  (println "Creating method")
  (amazon/put-method :restapi-id api-id
                     :resource-id proxy-id
                     :http-method http-method
                     :authorization-type "NONE"
                     :request-parameters {"method.request.path.proxy" true}))

(defn- maybe-create-method [api-id proxy-id]
  (or
    (find-method api-id proxy-id)
    (create-method api-id proxy-id)))

(defn- find-integration [api-id proxy-id]
  (try
    (amazon/get-integration :http-method http-method
                            :resource-id proxy-id
                            :restapi-id api-id)
    (catch Exception _ false)))

(defn- create-integration [api-id proxy-id function-arn region]
  (println "Creating integration")
  (amazon/put-integration :restapi-id api-id
                          :resource-id proxy-id
                          :http-method http-method
                          :integration-http-method "POST"
                          :type "AWS_PROXY"
                          :passthrough-behavior "WHEN_NO_MATCH"
                          :uri (target-arn function-arn region)))

(defn- maybe-create-integration [api-id proxy-id function-arn region]
  (or
    (find-integration api-id proxy-id)
    (create-integration api-id proxy-id function-arn region)))  

(def stage-name "production")

(defn find-stage [api-id]
  (try
    (amazon/get-stage :restapi-id api-id
                      :stage-name stage-name)
    (catch Exception _ false)))

(defn- create-deployment [api-id]
  (println "Creating deployment")
  (amazon/create-deployment :restapi-id api-id
                            :stage-name stage-name))

(defn- maybe-create-deployment [api-id]
  (or
    (find-stage api-id)
    (create-deployment api-id)))

(defn deploy [{{:keys [name]} :api-gateway} function-arn]
  (when name
    (let [[region account-id function-name] (lambda/get-arn-components function-arn)
          api-id (maybe-create-api name function-name region account-id)
          [root-id proxy-id] (get-resource-ids api-id)]
      (let [proxy-id (maybe-create-proxy-resource api-id root-id proxy-id)
            method-id (maybe-create-method api-id proxy-id)]
        (maybe-create-integration api-id proxy-id function-arn region)
        (maybe-create-deployment api-id)))))