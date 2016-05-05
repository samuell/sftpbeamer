/**
 * Created by xiaxi on 14/04/16.
 */
$(document).ready(function () {

    $("#host2-table-div").on('click', 'tbody>tr', function () {
        $(this).toggleClass('selected');
    });

    $('#host2-disconnect-btn').click(function(event) {
        event.preventDefault();
        disconnect_sftp('host2');
    });

    $(document).on('click', '.host2-folder-link', function(event) {
        event.preventDefault();
        var href = $(this).attr('href');
        var folder_name = $(this).text();
        $.ajax({
            type: "GET",
            url: href,
            dataType: "json",
            success: function(returnedData) {
                if (returnedData["error"]) {
                    change_modal_property("Error", returnedData["error"]);
                    var modal = $('#info_modal');
                    modal.one('hide.bs.modal', function (event) {
                        location.reload();
                    });
                    modal.modal({
                        keyboard: false,
                        backdrop: 'static'
                    });
                } else if (returnedData["exception"]) {
                    change_modal_property("Exception", returnedData["exception"]);
                    $('#info_modal').modal({
                        keyboard: false,
                        backdrop: 'static'
                    });
                } else {
                    var path = returnedData["path"];
                    $("#host2-path").append('<a class="host2-path-link" href="/sftp/list?path=' + path + '&source=host2">' + folder_name + '/</a>');
                    host2_upload_url = createUploadUrl("host2");
                    reloadTableData(returnedData["data"], path, "host2");
                }
            }
        });
    });

    $(document).on('click', '.host2-path-link', function(event) {
        event.preventDefault();
        var href = $(this).attr('href');

        $.ajax({
            type: "GET",
            url: href,
            dataType: "json",
            success: function(returnedData) {
                if (returnedData["error"]) {
                    change_modal_property("Error", returnedData["error"]);
                    var modal = $('#info_modal');
                    modal.one('hide.bs.modal', function (event) {
                        location.reload();
                    });
                    modal.modal({
                        keyboard: false,
                        backdrop: 'static'
                    });
                } else if (returnedData["exception"]) {
                    change_modal_property("Exception", returnedData["exception"]);
                    $('#info_modal').modal({
                        keyboard: false,
                        backdrop: 'static'
                    });
                } else {
                    var path = returnedData["path"];
                    $(".host2-path-link").each(function () {
                        if (extractPath($(this).attr('href')).length > path.length) {
                            $(this).remove();
                        }
                    });
                    host2_upload_url = createUploadUrl("host2");
                    reloadTableData(returnedData["data"], path, "host2");
                }
            }
        });
    });

    $('#host2-transfer-btn').click(function() {
        var fileData = [];
        var folderData = [];

        var selected_items = host2_table.api().rows('.selected').data();
        if (selected_items.length == 0) {
            change_modal_property("Information", "No files or folders are selected.");
            $('#info_modal').modal({
                keyboard: false,
                backdrop: 'static'
            });
        } else {
            selected_items.each(function (item) {
                if (item[2] == 'file') {
                    fileData.push(item[0]);
                }
                if (item[2] == 'folder') {
                    folderData.push(item[0]);
                }
            });

            var from_path = extractPath($('.host2-path-link:last').attr('href'));
            var to_path = extractPath($('.host1-path-link:last').attr('href'));


            var messageAddress = generateId(40);
            $.ajax({
                type: "POST",
                url: "/sftp/transfer",
                data: JSON.stringify({
                    "address": messageAddress,
                    "from": {"path": from_path, "name": "host2", "data": {"file": fileData, "folder": folderData}},
                    "to": {"path": to_path, "name": "host1"}
                }),
                dataType: "json",
                contentType: 'application/json; charset=utf-8',
                success: function (returnedData) {
                    if (returnedData["error"]) {
                        change_modal_property("Error", returnedData["error"]);
                        var modal = $('#info_modal');
                        modal.one('hide.bs.modal', function (event) {
                            location.reload();
                        });
                        modal.modal({
                            keyboard: false,
                            backdrop: 'static'
                        });
                    } else if (returnedData["exception"]) {
                        change_modal_property("Exception", returnedData["exception"]);
                        $('#info_modal').modal({
                            keyboard: false,
                            backdrop: 'static'
                        });
                    } else {
                        $('#transfer_progress_group').empty();
                        $('#transfer_modal').modal({
                            keyboard: false,
                            backdrop: 'static'
                        });
                        $('#transfer_modal').on('hide.bs.modal', {host: "host1"}, refresh_target_host);
                        var ws = create_ws_connection();
                        ws.onopen = function () {
                            ws.send(JSON.stringify({
                                "address": messageAddress}));
                        };
                        ws.onmessage = function (event) {
                            var message = JSON.parse(event.data);
                            if (message["status"] == "start") {
                                $('#transfer_progress_group').append('<div class="progress" style="margin-bottom: 10px;"> <div class="progress-bar progress-bar-info progress-bar-striped" role="progressbar" aria-valuemin="0" aria-valuemax="100"><span style="color: black;font-size: medium;">' + message["file"] +'</span> </div></div>');
                            }
                            if (message["status"] == "transferring") {
                                refresh_progress_bar(message);
                            }
                            if (message["status"] == "done") {
                                change_modal_property("Information", "File transfer is done.");
                                $('#info_modal').modal({
                                    keyboard: false,
                                    backdrop: 'static'
                                });
                            }
                        };
                        ws.onclose = function () {
                        }
                    }
                }
            });
        }
    });

    $('#host2-delete-btn').click(function() {
        var transferredData = [];

        var selected_items = host2_table.api().rows('.selected').data();
        if (selected_items.length == 0) {
            change_modal_property("Information", "No files or folders are selected.");
            $('#info_modal').modal({
                keyboard: false,
                backdrop: 'static'
            });
        } else {
            selected_items.each(function (item) {
                transferredData.push({"name": item[0], "type": item[2]});
            });

            var path = extractPath($('.host2-path-link:last').attr('href'));

            $.ajax({
                type: "POST",
                url: "/sftp/delete",
                data: JSON.stringify({"source": "host2", "path": path, "data": transferredData}),
                dataType: "json",
                contentType: 'application/json; charset=utf-8',
                statusCode: {
                    200: function () {
                        var url = "/sftp/list?path=" + path + "&source=host2";
                        $.ajax({
                            type: "GET",
                            url: url,
                            dataType: "json",
                            success: function (updatedData) {
                                reloadTableData(updatedData["data"], updatedData["path"], "host2");
                            }
                        });
                    },
                    500: function (returnedData) {
                        if (returnedData["error"]) {
                            change_modal_property("Error", returnedData["error"]);
                            var modal = $('#info_modal');
                            modal.one('hide.bs.modal', function (event) {
                                location.reload();
                            });
                            modal.modal({
                                keyboard: false,
                                backdrop: 'static'
                            });
                        } else if (returnedData["exception"]) {
                            change_modal_property("Exception", returnedData["exception"]);
                            $('#info_modal').modal({
                                keyboard: false,
                                backdrop: 'static'
                            });
                        }
                    }
                }
            });
        }
    });

    $('#host2-upload-btn').click(function (event) {
        $.ajax({
            url: "/sftp/upload?source=host2",
            method: "GET",
            contents: "text/plain",
            success: function(reference){
                host_upload_reference = reference;
                uploaded_files_array = [];
                progress_bar_group = {};
                finished_upload_array = [];
                upload_url = host2_upload_url;
                $('#upload_progress_group').empty();
                $('#upload_modal').modal({
                    keyboard: false,
                    backdrop: 'static'
                });
                $('#upload_modal').on('hide.bs.modal', {host: "host2"}, function (event) {
                    refresh_target_host(event);
                });
            }
        });
    });
    
});