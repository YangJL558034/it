using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Mes.Api.Data.Migrations
{
    /// <inheritdoc />
    public partial class RememberStation : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "StationCredentialId",
                table: "Session",
                type: "uuid",
                nullable: true);

            migrationBuilder.CreateTable(
                name: "StationCredential",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    UserId = table.Column<Guid>(type: "uuid", nullable: false),
                    TokenHash = table.Column<string>(type: "text", nullable: false),
                    Expires = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_StationCredential", x => x.Id);
                    table.ForeignKey(
                        name: "FK_StationCredential_Users_UserId",
                        column: x => x.UserId,
                        principalTable: "Users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_Session_StationCredentialId",
                table: "Session",
                column: "StationCredentialId");

            migrationBuilder.CreateIndex(
                name: "IX_StationCredential_TokenHash",
                table: "StationCredential",
                column: "TokenHash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_StationCredential_UserId",
                table: "StationCredential",
                column: "UserId");

            migrationBuilder.AddForeignKey(
                name: "FK_Session_StationCredential_StationCredentialId",
                table: "Session",
                column: "StationCredentialId",
                principalTable: "StationCredential",
                principalColumn: "Id",
                onDelete: ReferentialAction.Restrict);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_Session_StationCredential_StationCredentialId",
                table: "Session");

            migrationBuilder.DropTable(
                name: "StationCredential");

            migrationBuilder.DropIndex(
                name: "IX_Session_StationCredentialId",
                table: "Session");

            migrationBuilder.DropColumn(
                name: "StationCredentialId",
                table: "Session");
        }
    }
}
