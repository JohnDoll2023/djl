#!/usr/bin/env python
#
# Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file
# except in compliance with the License. A copy of the License is located at
#
# http://aws.amazon.com/apache2.0/
#
# or in the "LICENSE.txt" file accompanying this file. This file is distributed on an "AS IS"
# BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express or implied. See the License for
# the specific language governing permissions and limitations under the License.
import json
import logging
import os
from typing import List

from huggingface_hub import HfApi, ModelSearchArguments
from huggingface_hub import hf_hub_download
from huggingface_hub.hf_api import ModelInfo
from argparse import Namespace

ARCHITECTURES_2_TASK = {
    "ForQuestionAnswering": "question-answering",
    "ForTokenClassification": "token-classification",
    "ForSequenceClassification": "text-classification",
    "ForMultipleChoice": "text-classification",
    "ForMaskedLM": "fill-mask"
}


class HuggingfaceModels:

    def __init__(self, output_dir: str):
        self.output_dir = output_dir
        self.processed_models = {}

        output_path = os.path.join(output_dir, "processed_models.json")
        if os.path.exists(output_path):
            with open(output_path, "r") as f:
                self.processed_models = json.load(f)

        self.temp_dir = f"{self.output_dir}/tmp"

    def list_models(self, args: Namespace) -> List[dict]:
        languages = ModelSearchArguments().language
        api = HfApi()
        if args.model_name:
            models = api.list_models(filter="pytorch",
                                     search=args.model_name,
                                     sort="downloads",
                                     direction=-1,
                                     limit=args.limit)
            if not models:
                logging.warning(f"no model found: {args.model_name}.")
        else:
            models = api.list_models(filter=f"{args.category},pytorch",
                                     sort="downloads",
                                     direction=-1,
                                     limit=args.limit)
            if not models:
                logging.warning(f"no model matches category: {args.category}.")

        ret = []
        for model_info in models:
            model_id = model_info.modelId
            is_english = "en" in model_info.tags
            if not is_english:
                is_english = True
                for tag in model_info.tags:
                    if tag in languages and tag != 'en':
                        is_english = False
                        break

            if not is_english:
                logging.warning(f"Skip non-English model: {model_id}.")
                continue

            if self.processed_models.get(model_id):
                logging.info(f"Skip converted mode: {model_id}.")
                continue

            config = hf_hub_download(repo_id=model_id, filename="config.json")
            with open(config) as f:
                config = json.load(f)

            task, architecture = self.to_supported_task(config)
            if not task:
                logging.info(f"Unsupported model architecture: {architecture}")
                continue

            model = {
                "model_info": model_info,
                "config": config,
                "task": task,
            }
            ret.append(model)

        return ret

    def update_progress(self, model_info: ModelInfo, application: str,
                        result: bool, reason: str, size: int):
        status = {
            "result": "success" if result else "failed",
            "application": application,
            "sha1": model_info.sha,
            "size": size
        }
        if reason:
            status["reason"] = reason
        self.processed_models[model_info.modelId] = status

        dict_file = os.path.join(self.output_dir, "processed_models.json")
        with open(dict_file, 'w') as f:
            json.dump(self.processed_models,
                      f,
                      sort_keys=True,
                      indent=2,
                      ensure_ascii=False)

    @staticmethod
    def to_supported_task(config: dict):
        architecture = config.get("architectures")[0]
        for arch in ARCHITECTURES_2_TASK:
            if architecture.endswith(arch):
                return ARCHITECTURES_2_TASK[arch], architecture

        return None, architecture
