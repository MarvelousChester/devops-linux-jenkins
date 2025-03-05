import platform
import argparse
import sys
import os
import copy
import subprocess
import re

# Check the running OS
is_windows = platform.system() == "Windows"

"""
Command Line Arguments
 - Required Arguments  
    >> build_log_path : Path to the Unity build result log file (e.g. build_project.log)

 - Optional Argument 
    >> --exclude      : The key words want to exclude from error messages
    >> --debug        : Enable debug display
"""
# Required Arguments  
parser = argparse.ArgumentParser(description="Create and submit Bitbucket PR report form.")
parser.add_argument("build_log_path", help="Path to build_project.log file")

# Optional Argument 
parser.add_argument("--exclude", nargs='*', default=[], help="List of content that must be excluded from a line")
parser.add_argument("--debug", action="store_true", help="Enable debug mode")

# Template for error messages
ERROR_TEMPLATE = {
    "build_result": "",
    "system_error_messages": [],
    "exit_code": "",
    "unity_error": {
        "errors": []
    }
}

# DEBUGGING display functions
def cmd_debug_display(process_type, cli_cmd, cmd_return):
    print("\n---Build Log Extraction DEBUG INFO-------------------------------------------------------------------------------------------------")
    print(f"\{process_type} Processing")
    print(f"\nCommand: {cli_cmd}")
    print(f"\nReturn Code: {cmd_return.returncode}")
    print(f"\nSTDOUT:\n{cmd_return.stdout}")
    if cmd_return.stderr:
        print(f"STDERR:\n{cmd_return.stderr}")
    print("-------------------------------------------------------------------------------------------------------------------------------------")

def error_message_object_debug_display(error_messages):
    print("\n---Build Log Extraction DEBUG INFO-------------------------------------------------------------------------------------------------")
    print("\\ERROR_TEMPLATE")
    print(f"\nbuild_result: {error_messages['build_result']}")
    print(f"\nexit_code: {error_messages['exit_code']}")
    print(f"\nsystem_error_messages:\n{error_messages['system_error_messages']}")
    print(f"\nunity_error:\n{error_messages['unity_error']}")
    print("-------------------------------------------------------------------------------------------------------------------------------------")

# main function
def extract_error_log(build_log_path, exclude_keywords=[], debug=False):
    if debug:
        print(f"Extracting build error messages from {build_log_path}")
    
    """ Construct CLI commands for parsing the log file """
    # 1. Extract build status 
    grep_build_result_command = f"grep -i 'Build Finished, Result:' {build_log_path}"

    # 2.Extract error messages 
    # 'grep -i': case insensitive 
    # 'grep -E': use regular expression >> Search only for "error" with spaces before or after or both
    # 'sort -u': remove redundant lines  
    if is_windows:
        grep_error_command = f"bash -c \"grep -iE '(\\s+error|error\\s+|\\berror\\b)' {build_log_path} | sort -u\""
    else:
        grep_error_command = f"grep -iE '(\\s+error|error\\s+|\\berror\\b)' {build_log_path} | sort -u"

    if exclude_keywords:
        for keyword in exclude_keywords:
            grep_error_command += f" | grep -Ev \"{keyword}\""

    # 3. Extract exit code
    grep_exit_code_command = f"grep -A 1 '##### ExitCode' {build_log_path} | tail -n 1"

    """ Process Error Message Parsing """
    try:
        # Initialize the error message dictionary with the predefined template
        error_messages = copy.deepcopy(ERROR_TEMPLATE)

        # Get the buid result
        # Set the (check=False) to handle the 'Unexpected Termination' scenario
        build_result_process = subprocess.run(grep_build_result_command, shell=True, capture_output=True, text=True, check=False)

        if debug and build_result_process.stdout:
            cmd_debug_display("Build Result", grep_build_result_command, build_result_process)

        # Store the build result status
        if "Success" in build_result_process.stdout:
            error_messages["build_result"] = "Success"
        elif "Failure" in build_result_process.stdout:
            error_messages["build_result"] = "Failure"
        else:
            error_messages["build_result"] = "Unexpected Termination"


        # Get error messages from the log file
        error_process = subprocess.run(grep_error_command, shell=True, capture_output=True, text=True, check=False)

        if debug and error_process.stdout:
            cmd_debug_display("Error Message", grep_error_command, error_process)

        # Classify and store error messages
        for line in error_process.stdout.split("\n"):
            # skip the empty line
            if not line:
                continue

            if debug:
                print(f"Processing Line (repr): {repr(line)}")

            line = line.strip()

            # Extracting "source file path : error code : error message" 
            # The expected error message looks like >> Assets/Scripts/UI/EndModal.cs(1,17): error CS0234: The type or namespace name 'IdentityModel' does not exist
            if "Asset" in line:
                if debug:
                    print(" Found 'Asset' in line!")

                parts = line.split(":")

                if len(parts) == 3:
                    file_path_raw = parts[0].strip()      # e.g. Assets/Scripts/UI/EndModal.cs(1,17)
                    error_code = parts[1].strip()         # e.g. error CS0234
                    error_description = parts[2].strip()  # e.g. The type or namespace name 'IdentityModel' does not exist

                    # Extract line number using regex (e.g., EndModal.cs(1,17) → file: EndModal.cs, line: 1)
                    match = re.search(r"\((\d+),\d+\)$", file_path_raw)
                    if match:
                        line_number = match.group(1)  # Extract the first number (line number)
                        file_path = re.sub(r"\(\d+,\d+\)$", "", file_path_raw)  # Remove line number from the file path
                    else:
                        line_number = "N/A"  # Default value if no line number is found

                    # Store Unity error message
                    if file_path and error_code and error_description:
                        error_messages["unity_error"]["errors"].append({
                            "file_path": file_path,
                            "line_number": line_number,  # New key-value pair for line number
                            "error_code": error_code,
                            "error_description": error_description
                        })

            # Store non-Unity errors as system-level errors 
            else:
                error_messages["system_error_messages"].append(line.strip())
        

        # Extract exit code from the log
        exit_code_process = subprocess.run(grep_exit_code_command, shell=True, capture_output=True, text=True, check=False)
        if exit_code_process and exit_code_process.stdout:
            exit_code = exit_code_process.stdout.strip()
            if exit_code:
                error_messages["exit_code"] = exit_code


        if debug:
            error_message_object_debug_display(error_messages)

        # Return erro message object
        return error_messages


    except subprocess.CalledProcessError as e:
        print(f"Error occurred! Return Code: {e.returncode}")
        print(f"STDOUT: {e.stdout}")
        print(f"STDERR: {e.stderr}")

    except FileNotFoundError:
        print("Command not found! (grep or cat might not be installed)")
        sys.exit(127)
        
    except Exception as e:
        print(f"An unexpected exception occurred: {str(e)}") 
        sys.exit(1)


if __name__ == "__main__":
    # Parse command-line arguments
    args = parser.parse_args()

    if args.build_log_path:
        extract_error_log(args.build_log_path, args.exclude, args.debug)