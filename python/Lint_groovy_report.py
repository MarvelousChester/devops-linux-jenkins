import json
import sys
import os
import argparse
import requests
import uuid

def get_json_normalized(json_file):
    normalized_path = os.path.normpath(json_file)
    if not os.path.isfile(normalized_path):
        raise FileNotFoundError(f"File not found: {normalized_path}")
    with open(normalized_path, 'r') as f:
        data = json.load(f)
    return data

def count_lint_errors(report_data):
    total_errors = 0
    file_error_count = {}
    files = report_data.get("files", {})
    for file_path, file_data in files.items():
        errors = file_data.get("errors", [])
        count = len(errors)
        if count > 0:
            file_error_count[file_path] = count
            total_errors += count
    summary = f"Total lint errors: {total_errors}"
    for f, cnt in file_error_count.items():
        summary += f"\n{f}: {cnt} errors"
    return summary, total_errors

def find_file_path(filename, search_path):
    # If the provided filename exists directly, return its relative path.
    if os.path.isfile(filename):
        return os.path.relpath(filename, search_path)
    # Otherwise try to match based on the basename.
    base = os.path.basename(filename)
    for root, dirs, files in os.walk(search_path):
        if base in files:
            return os.path.relpath(os.path.join(root, base), search_path)
    return None

def process_annotations(report_data, search_path, external_ids):
    annotations = []
    # Map lint severity to Bitbucket severity
    severity_map = {"info": "LOW", "warning": "MEDIUM", "error": "HIGH"}
    files = report_data.get("files", {})
    for file_path, file_data in files.items():
        relative_path = find_file_path(file_path, search_path)
        if relative_path is None:
            print(f"File not found in project: {file_path}")
            continue
        relative_path = relative_path.replace("\\", "/")
        for error in file_data.get("errors", []):
            line_number = error.get("line")
            rule = error.get("rule", "")
            msg = error.get("msg", "")
            # Build the summary string
            summary = f"[{rule}] {msg}"
            # Truncate summary if it's too long (max 200 characters here)
            max_length = 200
            if len(summary) > max_length:
                summary = summary[:max_length] + "..."
            
            ext_id = f"{relative_path}-{line_number}-{rule}"
            if ext_id in external_ids:
                ext_id += f"-{uuid.uuid4()}"
            else:
                external_ids.add(ext_id)
            
            annotation = {
                "external_id": ext_id,
                "annotation_type": "CODE_SMELL",
                "path": relative_path,
                "line": line_number,
                "summary": summary,
                "severity": severity_map.get(error.get("severity", "").lower(), "LOW")
            }
            annotations.append(annotation)
    return annotations

def chunk_annotations(annotations, batch_size):
    for i in range(0, len(annotations), batch_size):
        yield annotations[i:i + batch_size]

def main():
    parser = argparse.ArgumentParser(
        description="Arguments for Bitbucket linting reports",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    # Two reports: one for Groovy files, one for Jenkins files.
    parser.add_argument("groovy_report_path", help="Path to the Groovy linting report JSON file")
    parser.add_argument("jenkins_report_path", help="Path to the Jenkins linting report JSON file")
    parser.add_argument("commit", help="Commit hash to send the report to")
    parser.add_argument("Result", choices=["Pass", "Fail"], help="Pass or Fail indicating the overall linting result")
    parser.add_argument("project_path", help="Path to the project root for relative file lookup")
    args = vars(parser.parse_args())

    # Environment variables and API endpoint setup.
    access_token = os.getenv('BITBUCKET_ACCESS_TOKEN')
    pr_repo = os.getenv('JOB_REPO')
    report_id = 'lint-test-report'
    url = f'{pr_repo}/commit/{args["commit"]}/reports/{report_id}'

    headers = {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "Authorization": "Bearer " + access_token
    }

    # Process both linting reports.
    groovy_data = get_json_normalized(args["groovy_report_path"])
    jenkins_data = get_json_normalized(args["jenkins_report_path"])

    groovy_summary, groovy_error_count = count_lint_errors(groovy_data)
    jenkins_summary, jenkins_error_count = count_lint_errors(jenkins_data)
    total_errors = groovy_error_count + jenkins_error_count

    # Combine summaries from both reports.
    details = f"Groovy Lint Report:\n{groovy_summary}\n\nJenkins Lint Report:\n{jenkins_summary}"
    result_str = "PASSED" if args["Result"] == "Pass" else "FAILED"
    datastring = "Minor Linting Errors" if args["Result"] == "Pass" else "Major linting error found!"

    report_payload = {
        "title": "Linting Report",
        "details": details,
        "report_type": "TEST",
        "reporter": "Jenkins",
        "result": result_str,
        "data": [
            {
                "type": "TEXT",
                "title": "Report Details",
                "value": datastring
            },
            {
                "type": "BOOLEAN",
                "title": "Linting check passed?",
                "value": True if args["Result"] == "Pass" else False
            }
        ]
    }
    report_json = json.dumps(report_payload)

    try:
        response = requests.put(url, data=report_json, headers=headers)
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        print(f"Initial Request: {e.request.body}")
        print(f"Request Headers: {e.request.headers}")
        print(f"Response Error: {json.dumps(e.response.json())}")
        exit(1)

    # Process annotations from both reports.
    external_ids = set()
    search_path = os.path.normpath(args["project_path"])
    annotations = []
    annotations += process_annotations(groovy_data, search_path, external_ids)
    annotations += process_annotations(jenkins_data, search_path, external_ids)

    # Limit to Bitbucket REST API constraints.
    max_annotations = 1000
    batch_size = 100
    annotations_to_send = annotations[:max_annotations]
    annotation_url = url + '/annotations'

    for idx, annotation_batch in enumerate(chunk_annotations(annotations_to_send, batch_size)):
        annotation_json = json.dumps(annotation_batch)
        try:
            response = requests.post(annotation_url, data=annotation_json, headers=headers)
            response.raise_for_status()
            print(f"Batch {idx + 1} sent successfully")
        except requests.exceptions.RequestException as e:
            print(f"Error with batch {idx + 1}: {e.request.body}")
            if e.response:
                print(f"Response Error: {json.dumps(e.response.json())}")
            else:
                print(f"General Exception: {e}")
            exit(1)

if __name__ == "__main__":
    main()
